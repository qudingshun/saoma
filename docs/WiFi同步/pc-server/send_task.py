"""下发一条装柜任务（P2/P4 用）
用法: python send_task.py ABCD1234567 1200 ASV12,XQB80 --note "夜班"
"""
import json
import sys
import urllib.request

import server


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return
    no, qty = sys.argv[1], int(sys.argv[2])
    prefixes = (sys.argv[3].split(",") if len(sys.argv) > 3 and not sys.argv[3].startswith("--") else [])
    note = ""
    if "--note" in sys.argv:
        note = sys.argv[sys.argv.index("--note") + 1]
    rules = [{
        "prefix": p, "allowDuplicate": False, "countEnabled": True,
        "checkHistory": False, "ignoreRepeat": False,
        "serialMin": "", "serialMax": "", "serialTailLen": 0
    } for p in prefixes if p]
    body = json.dumps({"containerNo": no, "totalQuantity": qty,
                       "note": note, "rules": rules}, ensure_ascii=False).encode()
    req = urllib.request.Request(
        f"http://127.0.0.1:{server.PORT}/api/v1/tasks", data=body, method="POST",
        headers={"Content-Type": "application/json", "X-Sync-Token": server.TOKEN})
    print(urllib.request.urlopen(req, timeout=5).read().decode())


if __name__ == "__main__":
    main()
