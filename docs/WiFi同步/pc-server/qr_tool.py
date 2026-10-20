"""生成配对二维码 pair.png（PDA 扫码枪扫描）"""
import socket
from pathlib import Path

import qrcode


def lan_ip() -> str:
    """取本机局域网 IP：优先真实出口网卡，失败回退私有网段枚举"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))      # 不会真的发包
        ip = s.getsockname()[0]
        if not ip.startswith("127."):
            return ip
    except OSError:
        pass
    finally:
        s.close()
    cands = []
    for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
        ip = info[4][0]
        if ip.startswith(("192.168.", "10.")) or ip.startswith("172."):
            cands.append(ip)
    return cands[0] if cands else "127.0.0.1"


def make_pair_qr(port: int, token: str):
    ip = lan_ip()
    payload = f"SAOMASYNC1|ip={ip}|port={port}|token={token}|ver=1"
    img = qrcode.make(payload)
    out = Path(__file__).resolve().parent / "pair.png"
    img.save(out)
    return payload, out


if __name__ == "__main__":
    import server
    payload, path = make_pair_qr(server.PORT, server.TOKEN)
    print("配对内容:", payload)
    print("二维码  :", path)
