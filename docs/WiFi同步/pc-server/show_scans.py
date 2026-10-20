"""查看 PDA 已上传的扫码记录
用法: python show_scans.py [柜号]
"""
import sys
import urllib.request

import server

no = sys.argv[1] if len(sys.argv) > 1 else ""
url = f"http://127.0.0.1:{server.PORT}/api/v1/scans?limit=50" + (f"&containerNo={no}" if no else "")
req = urllib.request.Request(url, headers={"X-Sync-Token": server.TOKEN})
print(urllib.request.urlopen(req, timeout=5).read().decode("utf-8"))
