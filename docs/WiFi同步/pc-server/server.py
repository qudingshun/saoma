"""
装柜 PDA WiFi 同步服务端  v1（P0/P1）
协议见 docs/WiFi同步/01-总体设计与接口契约.md

启动：
  .venv\\Scripts\\python.exe -m uvicorn server:app --host 0.0.0.0 --port 8000
"""
import json
import os
import secrets
import sqlite3
from contextlib import closing
from datetime import datetime, timezone, timedelta
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse, HTMLResponse

# ---------------- 配置 ----------------
ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
DATA.mkdir(exist_ok=True)
DB_PATH = DATA / "sync.db"
PORT = int(os.environ.get("SYNC_PORT", "8000"))

TZ = timezone(timedelta(hours=8))          # 固定东八区，避免机器时区设置影响


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


# ---------------- app ----------------
app = FastAPI(title="装柜 PDA 同步服务", version="1")
init_db()


def check_token(request: Request):
    if request.headers.get("X-Sync-Token") != TOKEN:
        raise HTTPException(status_code=401, detail={
            "error": {"code": "BAD_TOKEN", "message": "token 不匹配"}})


@app.get("/api/v1/health")
def health():
    with closing(db()) as conn:
        total = conn.execute("SELECT COUNT(*) c FROM scans").fetchone()["c"]
    return {"ok": True, "serverVer": 1, "time": now_iso(),
            "dbPath": str(DB_PATH), "pendingScans": total}


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
        conn.commit()
        total = conn.execute("SELECT COUNT(*) c FROM scans").fetchone()["c"]

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


@app.get("/", response_class=HTMLResponse)
def home():
    return f"""<!doctype html><meta charset="utf-8">
<h2>装柜 PDA 同步服务</h2>
<p>状态：<a href="/api/v1/health">/api/v1/health</a> ｜ 令牌：<code>{TOKEN}</code></p>
<p>配对：运行 <code>qr_tool.py</code> 生成 <code>pair.png</code>，或用
<code>IP:端口#{TOKEN}</code> 在 PDA 手动输入。</p>
<p>本机 IP 请用 <code>ipconfig</code> 查看，端口 {PORT}。</p>"""


@app.exception_handler(HTTPException)
async def http_exc_handler(request: Request, exc: HTTPException):
    return JSONResponse(status_code=exc.status_code, content={"error": exc.detail.get("error", exc.detail)}
                        if isinstance(exc.detail, dict) else {"error": {"code": "ERROR", "message": str(exc.detail)}})


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=PORT)
