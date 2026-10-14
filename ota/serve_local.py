#!/usr/bin/env python3
"""Serve the staged OTA tree (hioneos/ota/out) on the LAN, for testing Hione Update before the
real host. Same layout as the server: http://<this-pc>:8000/ota/<channel>/manifest.json.

Adds what `python -m http.server` lacks: single byte-range requests (206 / Content-Range), so
download resume can be tested, plus the cache headers the real server should send.

    hioneos/ota/serve_local.py                 # serves hioneos/ota/out on 0.0.0.0:8000
    hioneos/ota/serve_local.py --throttle 200  # cap at 200 KB/s to test progress and resume

Then on the device: Hione Update > About > Test server > http://<this-pc-ip>:8000/ota/
"""
import argparse
import os
import re
import socket
import time
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

RANGE_RE = re.compile(r"bytes=(\d*)-(\d*)$")


class Handler(SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    throttle = 0  # bytes per second, 0 = unlimited

    def end_headers(self):
        self.send_header("Accept-Ranges", "bytes")
        path = self.path.split("?", 1)[0]
        if path.endswith(".apk"):
            self.send_header("Cache-Control", "public, max-age=31536000, immutable")
        else:
            self.send_header("Cache-Control", "no-cache")
        super().end_headers()

    def guess_type(self, path):
        if str(path).endswith(".apk"):
            return "application/vnd.android.package-archive"
        if str(path).endswith(".sig"):
            return "text/plain"
        return super().guess_type(path)

    def copyfile(self, source, outputfile):
        self._send(source, outputfile, None)

    def _send(self, f, out, length):
        remaining = length
        while remaining is None or remaining > 0:
            n = 64 * 1024 if remaining is None else min(64 * 1024, remaining)
            buf = f.read(n)
            if not buf:
                break
            out.write(buf)
            if remaining is not None:
                remaining -= len(buf)
            if self.throttle:
                time.sleep(len(buf) / self.throttle)

    def do_GET(self):
        rng = self.headers.get("Range")
        if not rng:
            return super().do_GET()
        path = self.translate_path(self.path)
        if not os.path.isfile(path):
            self.send_error(404)
            return
        size = os.path.getsize(path)
        m = RANGE_RE.match(rng.strip())
        if not m:
            self.send_error(416)
            return
        a, b = m.groups()
        if a == "":
            start, end = max(0, size - int(b)), size - 1
        else:
            start, end = int(a), (int(b) if b else size - 1)
        if start >= size or end < start:
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{size}")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        end = min(end, size - 1)
        self.send_response(206)
        self.send_header("Content-Type", self.guess_type(path))
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        with open(path, "rb") as f:
            f.seek(start)
            self._send(f, self.wfile, end - start + 1)


def lan_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return "127.0.0.1"


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dir", default=os.path.join(here, "out"), help="staged tree (default hioneos/ota/out)")
    ap.add_argument("--bind", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8000)
    ap.add_argument("--throttle", type=int, default=0, help="KB/s cap, to watch progress and test resume")
    args = ap.parse_args()
    root = os.path.abspath(args.dir)
    if not os.path.isdir(os.path.join(root, "ota")):
        raise SystemExit(f"{root}/ota does not exist. Run publish.py first.")
    Handler.throttle = args.throttle * 1024
    httpd = ThreadingHTTPServer((args.bind, args.port), partial(Handler, directory=root))
    print(f"serving {root}")
    print(f"  device test server URL: http://{lan_ip()}:{args.port}/ota/")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
