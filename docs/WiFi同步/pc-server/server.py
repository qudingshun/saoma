"""
装柜 PDA WiFi 同步服务端  v1（P0/P1）
协议见 docs/WiFi同步/01-总体设计与接口契约.md

开发启动：
  .venv\\Scripts\\python.exe -m uvicorn server:app --host 0.0.0.0 --port 8000
EXE 启动：
  装柜同步服务.exe   （默认 http://本机IP:8000/，自动打开浏览器）
"""
import io
import json
import os
import re
import secrets
import shutil
import socket
import sqlite3
import sys
import threading
import webbrowser
import zipfile
from contextlib import closing
from datetime import datetime, timezone, timedelta
from pathlib import Path

import qrcode
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse, HTMLResponse, Response
from openpyxl import Workbook, load_workbook
from urllib.parse import quote

# ---------------- 配置 ----------------
# 打包成 exe 后，data 目录放在 exe 旁边（而不是临时解压目录）
if getattr(sys, "frozen", False):
    ROOT = Path(sys.executable).resolve().parent
else:
    ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
DATA.mkdir(exist_ok=True)
DB_PATH = DATA / "sync.db"
PORT = int(os.environ.get("SYNC_PORT", "8000"))

TZ = timezone(timedelta(hours=8))          # 固定东八区，避免机器时区设置影响


def lan_ip() -> str:
    """取本机局域网 IP：优先真实出口网卡，失败回退私有网段枚举"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        if not ip.startswith("127."):
            return ip
    except OSError:
        pass
    finally:
        s.close()
    cands = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip.startswith(("192.168.", "10.")) or ip.startswith("172."):
                cands.append(ip)
    except OSError:
        pass
    return cands[0] if cands else "127.0.0.1"


def pair_payload() -> str:
    return f"SAOMASYNC1|ip={lan_ip()}|port={PORT}|token={TOKEN}|ver=1"


def now_iso() -> str:
    return datetime.now(TZ).isoformat(timespec="milliseconds")


# ---------------- token ----------------
TOKEN_FILE = DATA / "token.txt"
if TOKEN_FILE.exists():
    TOKEN = TOKEN_FILE.read_text(encoding="utf-8").strip()
else:
    alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   # 去掉易混字符
    TOKEN = "".join(secrets.choice(alphabet) for _ in range(8))
    TOKEN_FILE.write_text(TOKEN, encoding="utf-8")


# ---------------- DB ----------------
SCHEMA = """
PRAGMA journal_mode=WAL;
PRAGMA synchronous=NORMAL;

CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL);
INSERT OR IGNORE INTO meta(k, v) VALUES('task_rev', '0');

CREATE TABLE IF NOT EXISTS tasks (
  container_no   TEXT PRIMARY KEY,
  total_quantity INTEGER NOT NULL DEFAULT 0,
  rules_json     TEXT NOT NULL DEFAULT '[]',
  note           TEXT NOT NULL DEFAULT '',
  deleted        INTEGER NOT NULL DEFAULT 0,
  rev            INTEGER NOT NULL,
  updated_at     TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tasks_rev ON tasks(rev);

CREATE TABLE IF NOT EXISTS scans (
  uid          TEXT PRIMARY KEY,
  device_id    TEXT,
  container_no TEXT,
  barcode      TEXT,
  full_code    TEXT,
  result       TEXT,
  scanned_at   TEXT,
  operator_id  TEXT,
  deleted_at   TEXT,
  received_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_scans_container ON scans(container_no, scanned_at);
CREATE INDEX IF NOT EXISTS idx_scans_barcode   ON scans(barcode);

CREATE TABLE IF NOT EXISTS acks (
  device_id   TEXT PRIMARY KEY,
  rev         INTEGER NOT NULL DEFAULT 0,
  app_version TEXT,
  at          TEXT NOT NULL
);

-- 多 PDA 管理（v1.10.0）：名称/文件夹/最近在线；扫描记录按设备分目录
CREATE TABLE IF NOT EXISTS devices (
  device_id   TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  folder      TEXT NOT NULL,
  app_version TEXT NOT NULL DEFAULT '',
  last_seen   TEXT NOT NULL,
  last_ip     TEXT NOT NULL DEFAULT '',
  created_at  TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_name   ON devices(name);
CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_folder ON devices(folder);

-- 规则库快照（v1.9.0）：单行；rev 每次上传 +1，PDA 据此判断是否有新版本
CREATE TABLE IF NOT EXISTS rulelib (
  id           INTEGER PRIMARY KEY CHECK (id = 1),
  rev          INTEGER NOT NULL,
  models_json  TEXT NOT NULL,
  rules_json   TEXT NOT NULL,
  updated_at   TEXT NOT NULL,
  updated_by   TEXT NOT NULL
);
"""


def db():
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    with closing(db()) as conn:
        conn.executescript(SCHEMA)
        conn.commit()


def next_task_rev(conn) -> int:
    conn.execute("UPDATE meta SET v = CAST(v AS INTEGER) + 1 WHERE k = 'task_rev'")
    return int(conn.execute("SELECT v FROM meta WHERE k = 'task_rev'").fetchone()["v"])


# ---------------- 电脑端 TSV 落盘（按日期 / 柜号，和 PDA 本地文件同格式） ----------------
RECORD_DIR = DATA / "装柜记录"
TRASH_DIR = DATA / "回收站"
TSV_HEADER = "\ufeff时间\t柜号\t条码\t操作员\n"


def _record_time(raw: str) -> tuple[str, str]:
    """ISO 时间 -> (日期 yyyy-MM-dd, 显示时间 yyyy-MM-dd HH:mm:ss)，固定东八区"""
    s = (raw or "").strip()
    try:
        dt = datetime.fromisoformat(s.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=TZ)
        dt = dt.astimezone(TZ)
        return dt.strftime("%Y-%m-%d"), dt.strftime("%Y-%m-%d %H:%M:%S")
    except ValueError:
        day = s[:10] if len(s) >= 10 else "未知日期"
        return day, (s.replace("T", " ")[:19] or s)


def _safe_file_name(name: str) -> str:
    cleaned = re.sub(r'[\\/:*?"<>|]', "_", (name or "").strip())
    return cleaned or "未填柜号"


DEVICE_RESERVED = {
    "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6",
    "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6",
    "LPT7", "LPT8", "LPT9",
}


def _safe_device_name(raw: str, fallback: str = "未命名PDA") -> str:
    """PDA 名称 → 安全文件夹名（保留中文/字母/数字/空格/下划线）"""
    s = re.sub(r'[\\/:*?"<>|\r\n\t]', "_", (raw or "").strip())
    s = s.strip(" .")[:32]
    if not s:
        s = fallback
    if s.upper() in DEVICE_RESERVED:
        s += "_PDA"
    return s


def _ensure_device(conn, device_id: str, app_version: str = "", ip: str = "", touch: bool = True):
    """登记/更新一台 PDA；返回 devices 行。调用方负责 commit。
    touch=False 用于启动时重建文件：只补登记，不刷新「最近同步」。"""
    device_id = (device_id or "").strip()[:64]
    if not device_id or device_id == "?":
        device_id = "PDA-未知"
    now = now_iso()
    row = conn.execute("SELECT * FROM devices WHERE device_id = ?", (device_id,)).fetchone()
    if row:
        if touch:
            conn.execute(
                "UPDATE devices SET last_seen=?, last_ip=?, "
                "app_version=CASE WHEN ?<>'' THEN ? ELSE app_version END WHERE device_id=?",
                (now, ip or row["last_ip"] or "", (app_version or "")[:32],
                 (app_version or "")[:32], device_id))
            return conn.execute("SELECT * FROM devices WHERE device_id = ?", (device_id,)).fetchone()
        return row

    base = _safe_device_name(device_id, "未命名PDA")
    name = base
    n = 2
    while (conn.execute("SELECT 1 FROM devices WHERE name=? OR folder=?", (name, name)).fetchone()
           or (RECORD_DIR / name).exists() or (TRASH_DIR / name).exists()):
        name = _safe_device_name(f"{base}-{n}", base)
        n += 1
    conn.execute(
        "INSERT INTO devices(device_id,name,folder,app_version,last_seen,last_ip,created_at) "
        "VALUES(?,?,?,?,?,?,?)",
        (device_id, name, name, (app_version or "")[:32], now, ip or "", now))
    return conn.execute("SELECT * FROM devices WHERE device_id = ?", (device_id,)).fetchone()


def _ip_of(request: Request) -> str:
    return request.client.host if request.client else ""


def _migrate_legacy_layout():
    """v1.10.0 前是 装柜记录/日期/柜号.tsv；升级后移到 _旧版混合文件_*，再按设备重建。"""
    stamp = datetime.now(TZ).strftime("%Y%m%d_%H%M%S")
    for base in (RECORD_DIR, TRASH_DIR):
        if not base.exists():
            continue
        legacy = [p for p in base.iterdir()
                  if p.is_dir() and re.fullmatch(r"\d{4}-\d{2}-\d{2}", p.name)
                  and any(f.is_file() and f.suffix.lower() == ".tsv" for f in p.iterdir())]
        if not legacy:
            continue
        dest = base / f"_旧版混合文件_{stamp}"
        dest.mkdir(parents=True, exist_ok=True)
        for p in legacy:
            target = dest / p.name
            if target.exists():
                target = dest / f"{p.name}_{stamp}"
            shutil.move(str(p), str(target))


def _write_tsv(path: Path, rows: list[sqlite3.Row]) -> None:
    """整文件重写（临时文件 + 替换，避免 Excel 打开时读到半截）"""
    path.parent.mkdir(parents=True, exist_ok=True)
    parts = [TSV_HEADER]
    for r in rows:
        _, shown = _record_time(r["scanned_at"])
        parts.append("\t".join([
            shown,
            r["container_no"] or "",
            r["barcode"] or "",
            r["operator_id"] or "",
        ]) + "\n")
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text("".join(parts), encoding="utf-8", newline="")
    os.replace(tmp, path)


def rebuild_container_files(conn, device_id: str, container_no: str) -> int:
    """把某台 PDA 的一个柜号记录按「PDA名/日期/柜号.tsv」重写；返回写出的文件数"""
    dev = _ensure_device(conn, device_id, touch=False)
    folder = dev["folder"]
    rows = conn.execute(
        "SELECT * FROM scans WHERE COALESCE(NULLIF(device_id, ''), 'PDA-未知') = ? "
        "AND COALESCE(container_no, '') = ? ORDER BY scanned_at, uid",
        (dev["device_id"], container_no or "")).fetchall()
    active: dict[str, list] = {}
    trash: dict[str, list] = {}
    for r in rows:
        day, _ = _record_time(r["scanned_at"])
        (trash if r["deleted_at"] else active).setdefault(day, []).append(r)

    name = _safe_file_name(container_no) + ".tsv"
    written = 0
    for base, groups in ((RECORD_DIR, active), (TRASH_DIR, trash)):
        dev_base = base / folder
        if dev_base.exists():
            for day_dir in list(dev_base.iterdir()):
                if not day_dir.is_dir() or day_dir.name in groups:
                    continue
                old = day_dir / name          # 该 PDA 这个日期已没有记录 -> 删掉旧文件
                if old.exists():
                    try:
                        old.unlink()
                    except OSError:
                        pass
                try:
                    day_dir.rmdir()           # 空目录顺手清理
                except OSError:
                    pass
        for day, items in groups.items():
            _write_tsv(dev_base / day / name, items)
            written += 1
    return written


def rebuild_all_exports() -> int:
    """启动/手动重建全部 PDA 的记录文件；返回 PDA 数量"""
    _migrate_legacy_layout()
    with closing(db()) as conn:
        conn.execute("UPDATE scans SET device_id='PDA-未知' "
                     "WHERE device_id IS NULL OR TRIM(device_id)=''")
        devices = {r["d"] for r in conn.execute(
            "SELECT DISTINCT COALESCE(NULLIF(device_id, ''), 'PDA-未知') AS d FROM scans")}
        for r in conn.execute("SELECT DISTINCT device_id FROM acks"):
            if r["device_id"]:
                devices.add(r["device_id"])
        for r in conn.execute("SELECT device_id FROM devices"):
            devices.add(r["device_id"])
        for d in devices:
            _ensure_device(conn, d, touch=False)
        conn.commit()
        pairs = conn.execute(
            "SELECT DISTINCT COALESCE(NULLIF(device_id, ''), 'PDA-未知') AS d, "
            "COALESCE(container_no, '') AS c FROM scans").fetchall()
        for row in pairs:
            rebuild_container_files(conn, row["d"], row["c"])
        conn.commit()
        return len(devices)


# ---------------- app ----------------
app = FastAPI(title="装柜 PDA 同步服务", version="1")
init_db()


@app.on_event("startup")
def _startup_rebuild_exports():
    """启动时按数据库重建电脑端 TSV（升级/换电脑后历史记录也有文件）"""
    try:
        n = rebuild_all_exports()
        print(f"电脑端记录文件已生成：{n} 台 PDA -> {RECORD_DIR}")
    except Exception as exc:          # 文件写失败不影响扫码同步
        print(f"电脑端 TSV 生成失败（不影响同步）：{exc}")


def check_token(request: Request):
    if request.headers.get("X-Sync-Token") != TOKEN:
        raise HTTPException(status_code=401, detail={
            "error": {"code": "BAD_TOKEN", "message": "token 不匹配"}})


@app.get("/api/v1/health")
def health():
    with closing(db()) as conn:
        total = conn.execute("SELECT COUNT(*) c FROM scans").fetchone()["c"]
        rl = conn.execute("SELECT rev FROM rulelib WHERE id = 1").fetchone()
        devs = conn.execute("SELECT COUNT(*) c FROM devices").fetchone()["c"]
    return {"ok": True, "serverVer": 1, "time": now_iso(),
            "dbPath": str(DB_PATH), "exportDir": str(RECORD_DIR),
            "rulelibRev": rl["rev"] if rl else 0,
            "devices": devs,
            "pendingScans": total}


@app.post("/api/v1/rebuild-exports")
def rebuild_exports(request: Request):
    """按数据库重建电脑端 TSV（换电脑/手工改过文件后可调用）"""
    check_token(request)
    n = rebuild_all_exports()
    return {"ok": True, "devices": n, "dir": str(RECORD_DIR)}


# ---------- 下行：PDA 拉任务 ----------
@app.get("/api/v1/tasks")
def pull_tasks(request: Request, since: int = 0, limit: int = 200):
    check_token(request)
    limit = max(1, min(limit, 1000))
    with closing(db()) as conn:
        _ensure_device(conn, request.headers.get("X-Device-Id"), "", _ip_of(request))
        rows = conn.execute(
            "SELECT * FROM tasks WHERE rev > ? ORDER BY rev LIMIT ?", (since, limit + 1)
        ).fetchall()
        conn.commit()
    has_more = len(rows) > limit
    rows = rows[:limit]
    tasks = [{
        "rev": r["rev"],
        "containerNo": r["container_no"],
        "totalQuantity": r["total_quantity"],
        "note": r["note"],
        "updatedAt": r["updated_at"],
        "deleted": bool(r["deleted"]),
        "rules": json.loads(r["rules_json"]),
    } for r in rows]
    next_rev = rows[-1]["rev"] if rows else since
    return {"serverVer": 1, "nextRev": next_rev, "hasMore": has_more, "tasks": tasks}


# ---------- 下行：PC 侧创建/更新任务（运维接口） ----------
@app.post("/api/v1/tasks")
async def upsert_task(request: Request):
    check_token(request)
    body = await request.json()
    no = (body.get("containerNo") or "").strip()
    if not no or not no.isalnum() or len(no) > 32:
        raise HTTPException(status_code=422, detail={
            "error": {"code": "VALIDATION", "message": "containerNo 必须是 1-32 位字母数字"}})
    rules = body.get("rules") or []
    with closing(db()) as conn:
        rev = next_task_rev(conn)
        conn.execute(
            "INSERT INTO tasks(container_no,total_quantity,rules_json,note,deleted,rev,updated_at) "
            "VALUES(?,?,?,?,?,?,?) "
            "ON CONFLICT(container_no) DO UPDATE SET "
            "total_quantity=excluded.total_quantity, rules_json=excluded.rules_json, "
            "note=excluded.note, deleted=excluded.deleted, rev=excluded.rev, updated_at=excluded.updated_at",
            (no, int(body.get("totalQuantity") or 0), json.dumps(rules, ensure_ascii=False),
             body.get("note") or "", 1 if body.get("deleted") else 0, rev, now_iso()))
        conn.commit()
    return {"ok": True, "rev": rev}


# ---------- 上行：PDA 提交扫码记录（幂等） ----------
@app.post("/api/v1/scans")
async def push_scans(request: Request):
    check_token(request)
    body = await request.json()
    device = body.get("deviceId") or request.headers.get("X-Device-Id") or "?"
    app_version = body.get("appVersion") or request.headers.get("X-App-Version") or ""
    scans = body.get("scans") or []
    if len(scans) > 1000:
        raise HTTPException(status_code=413, detail={
            "error": {"code": "TOO_LARGE", "message": "单批最多 1000 条"}})

    accepted = duplicated = 0
    rejected = []
    affected: set[str] = set()
    ts = now_iso()
    with closing(db()) as conn:
        dev = _ensure_device(conn, device, app_version, _ip_of(request))
        device_id = dev["device_id"]
        for s in scans:
            uid = (s.get("uid") or "").strip()
            if not uid:
                rejected.append({"uid": "", "reason": "missing uid"})
                continue
            exists = conn.execute("SELECT 1 FROM scans WHERE uid = ?", (uid,)).fetchone()
            conn.execute(
                "INSERT INTO scans(uid,device_id,container_no,barcode,full_code,result,"
                "scanned_at,operator_id,deleted_at,received_at) VALUES(?,?,?,?,?,?,?,?,?,?) "
                "ON CONFLICT(uid) DO UPDATE SET "
                "deleted_at=excluded.deleted_at, operator_id=excluded.operator_id, "
                "received_at=excluded.received_at",
                (uid, device_id, s.get("containerNo"), s.get("barcode"), s.get("fullCode"),
                 s.get("result"), s.get("scannedAt"), s.get("operatorId"),
                 s.get("deletedAt"), ts))
            if exists:
                duplicated += 1      # 未新增（可能只更新了 deletedAt），不算错误
            else:
                accepted += 1
            affected.add((s.get("containerNo") or "").strip())
        conn.commit()
        total = conn.execute("SELECT COUNT(*) c FROM scans").fetchone()["c"]
        # 落盘：该 PDA 每个受影响的柜号重写自己的日期文件（删除/撤回也会同步到回收站）
        for container_no in affected:
            try:
                rebuild_container_files(conn, device_id, container_no)
            except Exception as exc:
                print(f"TSV 写入失败（{device_id} / {container_no}）：{exc}")

    return {"accepted": accepted, "duplicated": duplicated, "rejected": rejected,
            "serverRevOut": total, "serverTime": now_iso(), "deviceId": device_id}


# ---------- 游标确认 ----------
@app.post("/api/v1/ack")
async def ack(request: Request):
    check_token(request)
    body = await request.json()
    device = body.get("deviceId") or request.headers.get("X-Device-Id") or "?"
    with closing(db()) as conn:
        dev = _ensure_device(conn, device, body.get("appVersion") or "", _ip_of(request))
        conn.execute(
            "INSERT INTO acks(device_id,rev,app_version,at) VALUES(?,?,?,?) "
            "ON CONFLICT(device_id) DO UPDATE SET rev=excluded.rev, "
            "app_version=excluded.app_version, at=excluded.at",
            (dev["device_id"], int(body.get("rev") or 0), body.get("appVersion"), now_iso()))
        conn.commit()
    return {"ok": True, "serverRev": int(body.get("rev") or 0)}


# ---------- 运维：查看已收记录 ----------
@app.get("/api/v1/scans")
def list_scans(request: Request, containerNo: str | None = None,
               deviceId: str | None = None,
               limit: int = 200, includeDeleted: bool = False):
    check_token(request)
    limit = max(1, min(limit, 1000))
    sql = ("SELECT s.*, COALESCE(d.name, s.device_id, '') AS device_name "
           "FROM scans s LEFT JOIN devices d ON d.device_id = s.device_id WHERE 1=1")
    args: list = []
    if containerNo:
        sql += " AND s.container_no = ?"
        args.append(containerNo)
    if deviceId:
        sql += " AND s.device_id = ?"
        args.append(deviceId)
    if not includeDeleted:
        sql += " AND s.deleted_at IS NULL"
    sql += " ORDER BY s.scanned_at DESC LIMIT ?"
    args.append(limit)
    with closing(db()) as conn:
        rows = conn.execute(sql, args).fetchall()
    return {"count": len(rows), "scans": [dict(r) for r in rows]}


# ---------- 多 PDA：列表 / 改名 / 下载记录（v1.10.0） ----------
def _device_dict(row) -> dict:
    return {
        "id": row["device_id"],
        "name": row["name"],
        "folder": row["folder"],
        "appVersion": row["app_version"] or "",
        "lastSeen": row["last_seen"],
        "lastIp": row["last_ip"] or "",
        "createdAt": row["created_at"],
        "scans": row["scans"] if "scans" in row.keys() else 0,
    }


@app.get("/api/v1/devices")
def list_devices(request: Request):
    check_token(request)
    with closing(db()) as conn:
        rows = conn.execute(
            "SELECT d.*, (SELECT COUNT(*) FROM scans s "
            " WHERE s.device_id = d.device_id AND s.deleted_at IS NULL) AS scans "
            "FROM devices d ORDER BY d.name").fetchall()
    return {"count": len(rows), "devices": [_device_dict(r) for r in rows]}


@app.post("/api/v1/devices/{device_id}/name")
async def rename_device(device_id: str, request: Request):
    """网页给 PDA 改名；文件夹随名称一起迁移（记录按 PDA 名分目录）"""
    check_token(request)
    body = await request.json()
    new_name = _safe_device_name(body.get("name") or "", "")
    if not new_name:
        raise HTTPException(status_code=422, detail={
            "error": {"code": "VALIDATION", "message": "名称不能为空"}})
    with closing(db()) as conn:
        row = conn.execute("SELECT * FROM devices WHERE device_id = ?", (device_id,)).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail={
                "error": {"code": "NOT_FOUND", "message": "PDA 不存在"}})
        if new_name == row["name"] and new_name == row["folder"]:
            return {"ok": True, "device": _device_dict(row)}
        dup = conn.execute(
            "SELECT device_id FROM devices WHERE (name = ? OR folder = ?) AND device_id <> ?",
            (new_name, new_name, device_id)).fetchone()
        if dup:
            raise HTTPException(status_code=409, detail={
                "error": {"code": "NAME_TAKEN", "message": f"名称已存在：{new_name}"}})

        old_folder = row["folder"]
        moved: list[tuple[Path, Path]] = []
        try:
            if new_name != old_folder:
                for base in (RECORD_DIR, TRASH_DIR):
                    src = base / old_folder
                    dst = base / new_name
                    if not src.exists():
                        if dst.exists():
                            raise HTTPException(status_code=409, detail={
                                "error": {"code": "FOLDER_TAKEN",
                                          "message": f"目录已存在：{new_name}"}})
                        continue
                    if dst.exists():
                        raise HTTPException(status_code=409, detail={
                            "error": {"code": "FOLDER_TAKEN",
                                      "message": f"目录已存在：{new_name}"}})
                    shutil.move(str(src), str(dst))
                    moved.append((src, dst))
            conn.execute("UPDATE devices SET name=?, folder=? WHERE device_id=?",
                         (new_name, new_name, device_id))
            conn.commit()
        except HTTPException:
            for src, dst in reversed(moved):
                if dst.exists() and not src.exists():
                    shutil.move(str(dst), str(src))
            raise
        except Exception as exc:
            for src, dst in reversed(moved):
                if dst.exists() and not src.exists():
                    shutil.move(str(dst), str(src))
            raise HTTPException(status_code=500, detail={
                "error": {"code": "RENAME_FAILED", "message": f"改名失败：{exc}"}})
        row = conn.execute("SELECT * FROM devices WHERE device_id = ?", (device_id,)).fetchone()
    return {"ok": True, "device": _device_dict(row)}


@app.get("/api/v1/devices/{device_id}/records.zip")
def download_device_records(device_id: str, request: Request):
    """下载某台 PDA 的全部记录表格（按 装柜记录/回收站/日期/柜号.tsv 打包）"""
    _check_token_query(request)
    with closing(db()) as conn:
        row = conn.execute("SELECT * FROM devices WHERE device_id = ?", (device_id,)).fetchone()
    if not row:
        raise HTTPException(status_code=404, detail={
            "error": {"code": "NOT_FOUND", "message": "PDA 不存在"}})
    folder = row["folder"]
    buf = io.BytesIO()
    count = 0
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for base, label in ((RECORD_DIR, "装柜记录"), (TRASH_DIR, "回收站")):
            src = base / folder
            if not src.exists():
                continue
            for f in sorted(src.rglob("*.tsv")):
                z.write(f, f"{folder}/{label}/{f.relative_to(src).as_posix()}")
                count += 1
    if count == 0:
        raise HTTPException(status_code=404, detail={
            "error": {"code": "NO_FILES", "message": "该 PDA 还没有记录文件"}})
    name = f"{folder}_装柜记录_{datetime.now(TZ).strftime('%Y%m%d_%H%M%S')}.zip"
    return Response(
        content=buf.getvalue(),
        media_type="application/zip",
        headers={"Content-Disposition": f"attachment; filename=pda_records.zip; "
                                        f"filename*=UTF-8''{quote(name)}"},
    )


# ---------- 规则库：PDA / 电脑网页 上传下载（v1.9.0） ----------
RULELIB_HEADERS = ["型号名称", "前缀", "可重复扫", "计入统计", "查历史串号", "忽略", "默认装柜数量"]
RULELIB_BACKUP_DIR = DATA / "规则库备份"


def _rulelib_row(conn) -> dict | None:
    row = conn.execute("SELECT * FROM rulelib WHERE id = 1").fetchone()
    if not row:
        return None
    return {
        "rev": row["rev"],
        "models": json.loads(row["models_json"]),
        "rules": json.loads(row["rules_json"]),
        "updatedAt": row["updated_at"],
        "updatedBy": row["updated_by"],
    }


def _save_rulelib(raw_models, raw_rules, device: str) -> dict:
    """校验并保存规则库快照（PDA 上传 / 电脑网页导入共用）；rev 每次 +1"""
    if not isinstance(raw_models, list) or not isinstance(raw_rules, list):
        raise HTTPException(status_code=422, detail={
            "error": {"code": "VALIDATION", "message": "models / rules 必须是数组"}})
    if len(raw_rules) > 20000:
        raise HTTPException(status_code=413, detail={
            "error": {"code": "TOO_LARGE", "message": "规则最多 20000 条"}})

    models = sorted({str(m or "").strip()[:64] for m in raw_models if str(m or "").strip()})
    rules: list[dict] = []
    seen: set[str] = set()
    for r in raw_rules:
        if not isinstance(r, dict):
            continue
        prefix = str(r.get("prefix") or "").strip().upper()
        if not re.fullmatch(r"[A-Z0-9]{1,64}", prefix):
            raise HTTPException(status_code=422, detail={
                "error": {"code": "VALIDATION", "message": f"前缀无效：{prefix[:32]}"}})
        if prefix in seen:
            continue                    # 同前缀只保留第一条（与 PDA 规则库唯一键一致）
        seen.add(prefix)
        name = str(r.get("name") or "").strip()[:64]
        has_default = "defaultQuantity" in r and str(r.get("defaultQuantity") or "").strip() != ""
        rules.append({
            "prefix": prefix,
            "name": name,
            "allowDuplicate": bool(r.get("allowDuplicate")),
            "countEnabled": bool(r.get("countEnabled", True)),
            "checkHistory": bool(r.get("checkHistory")),
            "ignoreRepeat": bool(r.get("ignoreRepeat")),
            # -1 = 文件没有第 7 列；保存时按前缀保留旧默认数量（与 PDA 导入口径一致）
            "defaultQuantity": _xlsx_int(r.get("defaultQuantity"), 0) if has_default else -1,
        })
    if not models and not rules:
        raise HTTPException(status_code=422, detail={
            "error": {"code": "EMPTY", "message": "规则库为空，未保存（避免误清空）"}})

    # 规则里出现的型号也补进型号列表（空型号仍保留）
    all_models = sorted(set(models) | {r["name"] for r in rules if r["name"]})
    rules.sort(key=lambda x: (x["name"], x["prefix"]))
    device = (device or "?")[:64]
    ts = now_iso()
    with closing(db()) as conn:
        old = _rulelib_row(conn)
        # 旧格式 Excel 没有第 7 列时，按前缀保留电脑端已有默认数量
        old_defaults = {
            r.get("prefix"): _xlsx_int(r.get("defaultQuantity"), 0)
            for r in (old["rules"] if old else [])
        }
        for r in rules:
            if r.get("defaultQuantity", -1) < 0:
                r["defaultQuantity"] = old_defaults.get(r["prefix"], 0)
        backup_name = ""
        if old:
            RULELIB_BACKUP_DIR.mkdir(exist_ok=True)
            base = f"规则库_覆盖前_{datetime.now(TZ).strftime('%Y%m%d_%H%M%S')}"
            backup_file = RULELIB_BACKUP_DIR / f"{base}.xlsx"
            n = 2
            while backup_file.exists():
                backup_file = RULELIB_BACKUP_DIR / f"{base}_{n}.xlsx"
                n += 1
            backup_file.write_bytes(_rulelib_to_xlsx(old["models"], old["rules"]))
            backup_name = backup_file.name
        row = conn.execute("SELECT rev FROM rulelib WHERE id = 1").fetchone()
        rev = (row["rev"] if row else 0) + 1
        conn.execute(
            "INSERT INTO rulelib(id,rev,models_json,rules_json,updated_at,updated_by) "
            "VALUES(1,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET rev=excluded.rev, "
            "models_json=excluded.models_json, rules_json=excluded.rules_json, "
            "updated_at=excluded.updated_at, updated_by=excluded.updated_by",
            (rev, json.dumps(all_models, ensure_ascii=False),
             json.dumps(rules, ensure_ascii=False), ts, device))
        conn.commit()
    return {"ok": True, "hasData": True, "rev": rev, "models": all_models,
            "rules": rules, "updatedAt": ts, "updatedBy": device,
            "backup": backup_name}


def _xlsx_flag(v, default: bool) -> bool:
    s = str(v or "").strip().lower()
    if s in ("1", "是", "true", "y", "yes", "√"):
        return True
    if s in ("0", "否", "false", "n", "no", "×", "-"):
        return False
    return default


def _xlsx_int(v, default: int = 0) -> int:
    s = str(v or "").strip()
    if not s:
        return default
    try:
        return max(0, min(999999, int(float(s))))
    except Exception:
        return default


def _rulelib_to_xlsx(models: list[str], rules: list[dict]) -> bytes:
    wb = Workbook()
    ws = wb.active
    ws.title = "规则库"
    ws.append(RULELIB_HEADERS)
    by_name: dict[str, list[dict]] = {}
    for r in rules:
        by_name.setdefault(r.get("name") or "", []).append(r)
    names = sorted(set(models) | set(by_name))
    for name in names:
        items = sorted(by_name.get(name, []), key=lambda x: x.get("prefix") or "")
        if not items:
            ws.append([name, "", "否", "是", "否", "否", "0"])
        for r in items:
            ws.append([
                name, r.get("prefix") or "",
                "是" if r.get("allowDuplicate") else "否",
                "是" if r.get("countEnabled", True) else "否",
                "是" if r.get("checkHistory") else "否",
                "是" if r.get("ignoreRepeat") else "否",
                str(_xlsx_int(r.get("defaultQuantity"), 0)),
            ])
    ws.column_dimensions["A"].width = 22
    ws.column_dimensions["B"].width = 18
    for col in "CDEFG":
        ws.column_dimensions[col].width = 10
    ws.freeze_panes = "A2"
    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def _parse_rulelib_xlsx(data: bytes) -> tuple[list[str], list[dict]]:
    try:
        wb = load_workbook(io.BytesIO(data), read_only=True, data_only=True)
    except Exception as exc:
        raise HTTPException(status_code=422, detail={
            "error": {"code": "BAD_XLSX", "message": f"Excel 无法解析：{exc}"}})
    ws = wb.worksheets[0] if wb.worksheets else None
    if ws is None:
        raise HTTPException(status_code=422, detail={
            "error": {"code": "BAD_XLSX", "message": "Excel 没有工作表"}})

    models: set[str] = set()
    rules: dict[str, dict] = {}
    name_first: bool | None = None
    for row in ws.iter_rows(values_only=True):
        vals = [("" if v is None else str(v)).strip() for v in (list(row) + [""] * 7)[:7]]
        if not any(vals):
            continue
        c0, c1 = vals[0], vals[1]
        if name_first is None:
            # 表头识别：型号在前（PDA 导出）或前缀在前（旧格式）
            if any(k in c0 for k in ("型号", "名称", "前缀", "条码")) or "前缀" in c1:
                name_first = any(k in c0 for k in ("型号", "名称")) and "前缀" in c1
                continue
            name_first = False
        name = (c0 if name_first else c1)[:64]
        prefix = re.sub(r"\s+", "", (c1 if name_first else c0)).upper()
        if not re.fullmatch(r"[A-Z0-9]{1,64}", prefix):
            if name:
                models.add(name)
            continue
        if name:
            models.add(name)
        if prefix not in rules:
            rule = {
                "prefix": prefix,
                "name": name,
                "allowDuplicate": _xlsx_flag(vals[2], False),
                "countEnabled": _xlsx_flag(vals[3], True),
                "checkHistory": _xlsx_flag(vals[4], False),
                "ignoreRepeat": _xlsx_flag(vals[5], False),
            }
            # 旧六列 Excel 不带第 7 列：不写该字段，保存时按前缀保留旧默认数量
            if vals[6].strip():
                rule["defaultQuantity"] = _xlsx_int(vals[6], 0)
            rules[prefix] = rule
    return sorted(models), list(rules.values())


def _check_token_query(request: Request):
    token = request.query_params.get("token") or request.headers.get("X-Sync-Token")
    if token != TOKEN:
        raise HTTPException(status_code=401, detail={
            "error": {"code": "BAD_TOKEN", "message": "token 不匹配"}})


@app.get("/api/v1/rulelib")
def get_rulelib(request: Request):
    """PDA 下载规则库（完整快照）；rev=0/hasData=false 表示电脑端还没有"""
    check_token(request)
    did = request.headers.get("X-Device-Id")
    with closing(db()) as conn:
        if did:
            _ensure_device(conn, did, request.headers.get("X-App-Version") or "", _ip_of(request))
            conn.commit()
        data = _rulelib_row(conn)
    if data is None:
        return {"ok": True, "hasData": False, "rev": 0, "models": [], "rules": [],
                "updatedAt": "", "updatedBy": ""}
    return {"ok": True, "hasData": True, **data}


@app.post("/api/v1/rulelib")
async def put_rulelib(request: Request):
    """PDA 上传本机规则库覆盖电脑端快照；每次上传 rev+1（其他 PDA 可看到新版本）"""
    check_token(request)
    body = await request.json()
    device = body.get("deviceId") or request.headers.get("X-Device-Id") or "?"
    if device and device != "?":
        with closing(db()) as conn:
            _ensure_device(conn, device, request.headers.get("X-App-Version") or "", _ip_of(request))
            conn.commit()
    return _save_rulelib(body.get("models") or [], body.get("rules") or [], device)


@app.get("/api/v1/rulelib.xlsx")
def download_rulelib_xlsx(request: Request):
    """电脑网页下载当前规则库为 XLSX（浏览器用 ?token= 鉴权）"""
    _check_token_query(request)
    with closing(db()) as conn:
        data = _rulelib_row(conn)
    if data is None:
        raise HTTPException(status_code=404, detail={
            "error": {"code": "NO_DATA", "message": "电脑端暂无规则库"}})
    blob = _rulelib_to_xlsx(data["models"], data["rules"])
    name = f"规则库_{datetime.now(TZ).strftime('%Y%m%d_%H%M%S')}.xlsx"
    return Response(
        content=blob,
        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        headers={"Content-Disposition": f"attachment; filename=rulelib.xlsx; filename*=UTF-8''{quote(name)}"},
    )


@app.post("/api/v1/rulelib/import")
async def import_rulelib_xlsx(request: Request):
    """电脑网页上传 XLSX 覆盖电脑端规则库（rev+1；浏览器先下载→Excel 修改→导入）"""
    check_token(request)
    data = await request.body()
    if not data:
        raise HTTPException(status_code=422, detail={
            "error": {"code": "EMPTY_FILE", "message": "没有收到文件内容"}})
    if len(data) > 20 * 1024 * 1024:
        raise HTTPException(status_code=413, detail={
            "error": {"code": "TOO_LARGE", "message": "文件最大 20MB"}})
    models, rules = _parse_rulelib_xlsx(data)
    if not models and not rules:
        raise HTTPException(status_code=422, detail={
            "error": {"code": "EMPTY", "message": "Excel 没有有效规则（第一列=型号，第二列=前缀）"}})
    device = request.headers.get("X-Device-Id") or "PC-WEB"
    return _save_rulelib(models, rules, device)


@app.get("/api/v1/pair")
def pair_info():
    return {"payload": pair_payload(), "ip": lan_ip(), "port": PORT, "token": TOKEN, "ver": 1}


@app.get("/api/v1/pair.png")
def pair_png():
    img = qrcode.make(pair_payload())
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return Response(content=buf.getvalue(), media_type="image/png")


@app.get("/", response_class=HTMLResponse)
def home():
    url = f"http://{lan_ip()}:{PORT}"
    return f"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>装柜同步服务</title>
<style>
 body{{font-family:"Microsoft YaHei",sans-serif;margin:24px;color:#222;background:#fafafa}}
 h2{{margin:0 0 12px}} .card{{background:#fff;border:1px solid #ddd;border-radius:8px;padding:16px;margin:12px 0;max-width:900px}}
 code{{background:#f2f2f2;padding:1px 4px;border-radius:3px}}
 img{{width:260px;height:260px;border:1px solid #eee}}
 .ok{{color:#2e7d32;font-weight:bold}} .muted{{color:#777;font-size:13px}}
 table{{border-collapse:collapse;width:100%;font-size:13px}} th,td{{border-bottom:1px solid #eee;padding:4px 6px;text-align:left}}
</style></head><body>
<h2>装柜 PDA 同步服务 <span class="ok">运行中</span></h2>
<div class="card">
  <p>电脑地址：<code>{url}</code>　端口：<b>{PORT}</b></p>
  <p>配对令牌：<code>{TOKEN}</code>　（PDA 手动输入：<code>{lan_ip()}:{PORT}#{TOKEN}</code>）</p>
  <p>用 PDA「WiFi 同步 → 扫描配对码」扫描下面的二维码：</p>
  <img src="/api/v1/pair.png" alt="pair qr">
  <p class="muted">电脑与 PDA 必须在同一 WiFi/局域网；Windows 防火墙需放行 {PORT} 端口（专用网络）。</p>
  <p>电脑端记录文件：<code>{RECORD_DIR}</code><br>
  <span class="muted">按「PDA 名称\\日期\\柜号.tsv」自动生成（Excel 可直接打开）；删除的记录在 <code>{TRASH_DIR}</code>。</span></p>
</div>
<div class="card">
  <p>PDA 设备（多台）　<button onclick="loadDevices()" style="font-size:13px">刷新设备</button></p>
  <p class="muted">PDA 首次同步会自动登记，默认名称为设备号；可点「改名」（记录文件夹会一起改名）。
  每台 PDA 的记录单独存放在 <code>data\\装柜记录\\PDA名称\\日期\\柜号.tsv</code>，互不混合。</p>
  <table id="dev"><thead><tr><th>PDA 名称</th><th>设备号</th><th>最近同步</th><th>记录数</th><th>操作</th></tr></thead><tbody></tbody></table>
  <p class="muted">「下载记录 ZIP」会把该 PDA 的装柜记录 + 回收站按日期/柜号打包下载。</p>
</div>
<div class="card">
  <p>最近收到的扫码记录（最多 20 条，不含已删除）
    　PDA：<select id="devsel" onchange="loadScans()" style="font-size:13px"><option value="">全部 PDA</option></select>
  </p>
  <table id="t"><thead><tr><th>时间</th><th>PDA</th><th>柜号</th><th>条码</th><th>结果</th><th>操作员</th></tr></thead><tbody></tbody></table>
  <p class="muted">接口：<a href="/api/v1/health">/api/v1/health</a> ｜ 本页面仅限局域网访问</p>
</div>
<div class="card">
  <p>规则库（PDA 上传 / 下载）</p>
  <p id="rl">加载中…</p>
  <p>
    <a href="/api/v1/rulelib.xlsx?token={TOKEN}">下载 Excel（当前规则库）</a>
    ｜ 导入 Excel：
    <input type="file" id="rlfile" accept=".xlsx" style="font-size:13px">
    <button onclick="importRL()" style="font-size:13px">上传覆盖电脑端</button>
  </p>
  <p id="rlmsg" class="muted"></p>
  <p class="muted">修改方法：先「下载 Excel」→ 用 Excel/WPS 改型号/前缀/开关 → 「上传覆盖电脑端」；<br>
  每次覆盖前自动备份旧快照到 <code>data/规则库备份/</code>，然后各 PDA 在「WiFi 同步 → 规则库同步」点「下载并覆盖本机规则库」（PDA 也会先自动备份）。</p>
</div>
<script>
const TOKEN='{TOKEN}';
async function loadDevices(){{
  try {{
    const r=await fetch('/api/v1/devices',{{headers:{{'X-Sync-Token':TOKEN}}}});
    const d=await r.json();
    const list=d.devices||[];
    const tb=document.querySelector('#dev tbody'); tb.innerHTML='';
    if(!list.length){{
      const tr=document.createElement('tr');
      const td=document.createElement('td'); td.colSpan=5; td.textContent='暂无 PDA：PDA 首次同步后会自动出现';
      tr.appendChild(td); tb.appendChild(tr);
    }}
    const sel=document.getElementById('devsel'); const cur=sel.value;
    sel.innerHTML='<option value="">全部 PDA</option>';
    list.forEach(x=>{{
      const o=document.createElement('option'); o.value=x.id; o.textContent=x.name; sel.appendChild(o);
      const tr=document.createElement('tr');
      const tdName=document.createElement('td'); tdName.textContent=x.name; tr.appendChild(tdName);
      const tdId=document.createElement('td'); tdId.innerHTML='<code></code>'; tdId.querySelector('code').textContent=x.id; tr.appendChild(tdId);
      const tdSeen=document.createElement('td'); tdSeen.textContent=(x.lastSeen||'').replace('T',' ').slice(0,19); tr.appendChild(tdSeen);
      const tdCount=document.createElement('td'); tdCount.textContent=x.scans||0; tr.appendChild(tdCount);
      const tdAct=document.createElement('td');
      const btn=document.createElement('button'); btn.textContent='改名'; btn.style.fontSize='12px';
      btn.onclick=()=>renameDev(x.id,x.name); tdAct.appendChild(btn);
      const zip=document.createElement('a'); zip.textContent=' 下载记录 ZIP';
      zip.href='/api/v1/devices/'+encodeURIComponent(x.id)+'/records.zip?token='+encodeURIComponent(TOKEN);
      tdAct.appendChild(zip); tr.appendChild(tdAct);
      tb.appendChild(tr);
    }});
    sel.value=cur;
  }} catch(e) {{}}
}}
async function renameDev(id, oldName){{
  const name=prompt('PDA 名称（记录文件夹会一起改名）', oldName);
  if(name===null || name.trim()==='' || name.trim()===oldName) return;
  const r=await fetch('/api/v1/devices/'+encodeURIComponent(id)+'/name',{{
    method:'POST', headers:{{'X-Sync-Token':TOKEN,'Content-Type':'application/json'}},
    body:JSON.stringify({{name:name.trim()}})
  }});
  const d=await r.json();
  if(!r.ok){{ alert('改名失败：'+((d.error&&d.error.message)||('HTTP '+r.status))); return; }}
  await loadDevices(); await loadScans();
}}
async function loadScans(){{
  try {{
    const dev=document.getElementById('devsel').value;
    let url='/api/v1/scans?limit=20';
    if(dev) url+='&deviceId='+encodeURIComponent(dev);
    const r=await fetch(url,{{headers:{{'X-Sync-Token':TOKEN}}}});
    const d=await r.json();
    const b=document.querySelector('#t tbody'); b.innerHTML='';
    (d.scans||[]).forEach(s=>{{
      const tr=document.createElement('tr');
      [s.scanned_at,s.device_name||s.device_id,s.container_no,s.barcode,s.result,s.operator_id||''].forEach(v=>{{
        const td=document.createElement('td'); td.textContent=v||''; tr.appendChild(td);
      }});
      b.appendChild(tr);
    }});
    if(!(d.scans||[]).length){{
      const tr=document.createElement('tr');
      const td=document.createElement('td'); td.colSpan=6; td.textContent='暂无记录';
      tr.appendChild(td); b.appendChild(tr);
    }}
  }} catch(e) {{}}
}}
loadDevices();
loadScans();
</script>
<script>
async function loadRL(){{
  try {{
    const r=await fetch('/api/v1/rulelib',{{headers:{{'X-Sync-Token':'{TOKEN}'}}}});
    const d=await r.json();
    const el=document.getElementById('rl');
    if(!d.hasData){{ el.textContent='电脑端暂无规则库：请在一台 PDA 的「WiFi 同步」页点「上传本机规则库」，或在下面导入 Excel。'; return; }}
    el.innerHTML='<b>'+d.models.length+'</b> 个型号 / <b>'+d.rules.length+'</b> 条规则，rev '+d.rev+
      '<br><span class="muted">更新：'+(d.updatedAt||'')+' ｜ 来源：'+(d.updatedBy||'')+'</span>';
  }} catch(e) {{}}
}}
async function importRL(){{
  const f=document.getElementById('rlfile').files[0];
  const msg=document.getElementById('rlmsg');
  if(!f){{ alert('请先选择 .xlsx 文件'); return; }}
  if(!confirm('将用「'+f.name+'」覆盖电脑端规则库（rev+1），继续？')) return;
  msg.textContent='上传中…';
  try {{
    const r=await fetch('/api/v1/rulelib/import',{{method:'POST',
      headers:{{'X-Sync-Token':'{TOKEN}','X-Device-Id':'PC-WEB','Content-Type':'application/octet-stream'}},
      body:f}});
    const d=await r.json();
    if(!r.ok) throw new Error((d.error&&d.error.message)||('HTTP '+r.status));
    alert('已导入：'+d.models.length+' 个型号 / '+d.rules.length+' 条规则，rev '+d.rev+'。\\n覆盖前备份：'+
      (d.backup ? ('data/规则库备份/'+d.backup) : '（首次保存，无旧数据）')+
      '\\n请到 PDA 点「下载并覆盖本机规则库」。');
    msg.textContent='';
    document.getElementById('rlfile').value='';
    loadRL();
  }} catch(e) {{ msg.textContent='导入失败：'+e.message; }}
}}
loadRL();
</script>
</body></html>"""


@app.exception_handler(HTTPException)
async def http_exc_handler(request: Request, exc: HTTPException):
    return JSONResponse(status_code=exc.status_code, content={"error": exc.detail.get("error", exc.detail)}
                        if isinstance(exc.detail, dict) else {"error": {"code": "ERROR", "message": str(exc.detail)}})


if __name__ == "__main__":
    import uvicorn

    # Windows 控制台用 UTF-8，避免中文提示乱码
    if sys.platform == "win32":
        try:
            sys.stdout.reconfigure(encoding="utf-8")
            sys.stderr.reconfigure(encoding="utf-8")
            import ctypes
            ctypes.windll.kernel32.SetConsoleOutputCP(65001)
        except Exception:
            pass

    print("=" * 60)
    print(" 装柜 PDA 同步服务")
    print(f" 本机访问：http://127.0.0.1:{PORT}/")
    print(f" 局域网  ：http://{lan_ip()}:{PORT}/")
    print(f" 令牌    ：{TOKEN}")
    print(f" 数据目录：{DATA}")
    print(" 关闭本窗口 = 停止服务")
    print("=" * 60)

    if os.environ.get("SYNC_NO_BROWSER") != "1":
        threading.Timer(1.8, lambda: webbrowser.open(f"http://127.0.0.1:{PORT}/")).start()
    uvicorn.run(app, host="0.0.0.0", port=PORT)
