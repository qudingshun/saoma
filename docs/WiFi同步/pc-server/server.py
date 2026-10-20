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
import socket
import sqlite3
import sys
import threading
import webbrowser
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
TSV_HEADER = "\ufeff时间\t柜号\t条码\t完整码\t结果\t操作员\n"

RESULT_LABELS = {
    "OK": "正常",
    "NOT_IN_LIST": "不在清单",
    "OVER_LIMIT": "超量",
    "SERIAL_MISMATCH": "串号",
    "DUPLICATE": "重复",
    "AUX": "辅助",
}


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


def _write_tsv(path: Path, rows: list[sqlite3.Row]) -> None:
    """整文件重写（临时文件 + 替换，避免 Excel 打开时读到半截）"""
    path.parent.mkdir(parents=True, exist_ok=True)
    parts = [TSV_HEADER]
    for r in rows:
        _, shown = _record_time(r["scanned_at"])
        result = (r["result"] or "").upper()
        parts.append("\t".join([
            shown,
            r["container_no"] or "",
            r["barcode"] or "",
            r["full_code"] or "",
            RESULT_LABELS.get(result, r["result"] or ""),
            r["operator_id"] or "",
        ]) + "\n")
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text("".join(parts), encoding="utf-8", newline="")
    os.replace(tmp, path)


def rebuild_container_files(conn, container_no: str) -> int:
    """把一个柜号的记录按「日期/柜号.tsv」重写到 装柜记录 / 回收站；返回写出的文件数"""
    rows = conn.execute(
        "SELECT * FROM scans WHERE COALESCE(container_no, '') = ? "
        "ORDER BY scanned_at, uid", (container_no or "",)).fetchall()
    active: dict[str, list] = {}
    trash: dict[str, list] = {}
    for r in rows:
        day, _ = _record_time(r["scanned_at"])
        (trash if r["deleted_at"] else active).setdefault(day, []).append(r)

    name = _safe_file_name(container_no) + ".tsv"
    written = 0
    for base, groups in ((RECORD_DIR, active), (TRASH_DIR, trash)):
        if base.exists():
            for day_dir in list(base.iterdir()):
                if not day_dir.is_dir() or day_dir.name in groups:
                    continue
                old = day_dir / name          # 该柜号在这个日期已没有记录 -> 删掉旧文件
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
            _write_tsv(base / day / name, items)
            written += 1
    return written


def rebuild_all_exports() -> int:
    """启动时/手动重建全部柜号的 TSV；返回柜号数"""
    with closing(db()) as conn:
        rows = conn.execute(
            "SELECT DISTINCT COALESCE(container_no, '') AS c FROM scans ORDER BY c").fetchall()
        for row in rows:
            rebuild_container_files(conn, row["c"])
        return len(rows)


# ---------------- app ----------------
app = FastAPI(title="装柜 PDA 同步服务", version="1")
init_db()


@app.on_event("startup")
def _startup_rebuild_exports():
    """启动时按数据库重建电脑端 TSV（升级/换电脑后历史记录也有文件）"""
    try:
        n = rebuild_all_exports()
        print(f"电脑端 TSV 已生成：{n} 个柜号 -> {RECORD_DIR}")
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
    return {"ok": True, "serverVer": 1, "time": now_iso(),
            "dbPath": str(DB_PATH), "exportDir": str(RECORD_DIR),
            "rulelibRev": rl["rev"] if rl else 0,
            "pendingScans": total}


@app.post("/api/v1/rebuild-exports")
def rebuild_exports(request: Request):
    """按数据库重建电脑端 TSV（换电脑/手工改过文件后可调用）"""
    check_token(request)
    n = rebuild_all_exports()
    return {"ok": True, "containers": n, "dir": str(RECORD_DIR)}


# ---------- 下行：PDA 拉任务 ----------
@app.get("/api/v1/tasks")
def pull_tasks(request: Request, since: int = 0, limit: int = 200):
    check_token(request)
    limit = max(1, min(limit, 1000))
    with closing(db()) as conn:
        rows = conn.execute(
            "SELECT * FROM tasks WHERE rev > ? ORDER BY rev LIMIT ?", (since, limit + 1)
        ).fetchall()
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
    scans = body.get("scans") or []
    if len(scans) > 1000:
        raise HTTPException(status_code=413, detail={
            "error": {"code": "TOO_LARGE", "message": "单批最多 1000 条"}})

    accepted = duplicated = 0
    rejected = []
    affected: set[str] = set()
    ts = now_iso()
    with closing(db()) as conn:
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
                (uid, device, s.get("containerNo"), s.get("barcode"), s.get("fullCode"),
                 s.get("result"), s.get("scannedAt"), s.get("operatorId"),
                 s.get("deletedAt"), ts))
            if exists:
                duplicated += 1      # 未新增（可能只更新了 deletedAt），不算错误
            else:
                accepted += 1
            affected.add((s.get("containerNo") or "").strip())
        conn.commit()
        total = conn.execute("SELECT COUNT(*) c FROM scans").fetchone()["c"]
        # 落盘：每个受影响的柜号重写自己的日期文件（删除/撤回也会同步到回收站）
        for container_no in affected:
            try:
                rebuild_container_files(conn, container_no)
            except Exception as exc:
                print(f"TSV 写入失败（{container_no}）：{exc}")

    return {"accepted": accepted, "duplicated": duplicated, "rejected": rejected,
            "serverRevOut": total, "serverTime": now_iso()}


# ---------- 游标确认 ----------
@app.post("/api/v1/ack")
async def ack(request: Request):
    check_token(request)
    body = await request.json()
    device = body.get("deviceId") or "?"
    with closing(db()) as conn:
        conn.execute(
            "INSERT INTO acks(device_id,rev,app_version,at) VALUES(?,?,?,?) "
            "ON CONFLICT(device_id) DO UPDATE SET rev=excluded.rev, "
            "app_version=excluded.app_version, at=excluded.at",
            (device, int(body.get("rev") or 0), body.get("appVersion"), now_iso()))
        conn.commit()
    return {"ok": True, "serverRev": int(body.get("rev") or 0)}


# ---------- 运维：查看已收记录 ----------
@app.get("/api/v1/scans")
def list_scans(request: Request, containerNo: str | None = None,
               limit: int = 200, includeDeleted: bool = False):
    check_token(request)
    limit = max(1, min(limit, 1000))
    sql = "SELECT * FROM scans WHERE 1=1"
    args: list = []
    if containerNo:
        sql += " AND container_no = ?"
        args.append(containerNo)
    if not includeDeleted:
        sql += " AND deleted_at IS NULL"
    sql += " ORDER BY scanned_at DESC LIMIT ?"
    args.append(limit)
    with closing(db()) as conn:
        rows = conn.execute(sql, args).fetchall()
    return {"count": len(rows), "scans": [dict(r) for r in rows]}


# ---------- 规则库：PDA / 电脑网页 上传下载（v1.9.0） ----------
RULELIB_HEADERS = ["型号名称", "前缀", "可重复扫", "计入统计", "查历史串号", "忽略"]
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
        rules.append({
            "prefix": prefix,
            "name": name,
            "allowDuplicate": bool(r.get("allowDuplicate")),
            "countEnabled": bool(r.get("countEnabled", True)),
            "checkHistory": bool(r.get("checkHistory")),
            "ignoreRepeat": bool(r.get("ignoreRepeat")),
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
            ws.append([name, "", "否", "是", "否", "否"])
        for r in items:
            ws.append([
                name, r.get("prefix") or "",
                "是" if r.get("allowDuplicate") else "否",
                "是" if r.get("countEnabled", True) else "否",
                "是" if r.get("checkHistory") else "否",
                "是" if r.get("ignoreRepeat") else "否",
            ])
    ws.column_dimensions["A"].width = 22
    ws.column_dimensions["B"].width = 18
    for col in "CDEF":
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
        vals = [("" if v is None else str(v)).strip() for v in (list(row) + [""] * 6)[:6]]
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
        rules.setdefault(prefix, {
            "prefix": prefix,
            "name": name,
            "allowDuplicate": _xlsx_flag(vals[2], False),
            "countEnabled": _xlsx_flag(vals[3], True),
            "checkHistory": _xlsx_flag(vals[4], False),
            "ignoreRepeat": _xlsx_flag(vals[5], False),
        })
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
    with closing(db()) as conn:
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
  <span class="muted">按「日期\\柜号.tsv」自动生成（Excel 可直接打开）；删除的记录在 <code>{TRASH_DIR}</code>。</span></p>
</div>
<div class="card">
  <p>最近收到的扫码记录（最多 20 条，不含已删除）</p>
  <table id="t"><thead><tr><th>时间</th><th>柜号</th><th>条码</th><th>结果</th><th>操作员</th></tr></thead><tbody></tbody></table>
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
fetch('/api/v1/scans?limit=20',{{headers:{{'X-Sync-Token':'{TOKEN}'}}}})
 .then(r=>r.json()).then(d=>{{
   const b=document.querySelector('#t tbody');
   d.scans.forEach(s=>{{
     const tr=document.createElement('tr');
     [s.scanned_at,s.container_no,s.barcode,s.result,s.operator_id||''].forEach(v=>{{
       const td=document.createElement('td'); td.textContent=v||''; tr.appendChild(td);
     }});
     b.appendChild(tr);
   }});
 }}).catch(e=>{{}});
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
