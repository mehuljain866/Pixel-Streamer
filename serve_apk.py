#!/usr/bin/env python3
"""
Tiny zero-dependency HTTP server to serve the compiled Pixel Streamer APK over your local Wi-Fi
so Android TV's Downloader app or TV browser can download and install it directly.
"""
import functools
import http.server
import os
import socket
import socketserver
import sys
from pathlib import Path

PORT = 8088
ROOT = Path(__file__).resolve().parent
DIST_APKS = sorted((ROOT / "dist").glob("*.apk"), key=lambda p: p.stat().st_mtime, reverse=True) if (ROOT / "dist").exists() else []
APK_CANDIDATES = DIST_APKS + [
    ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk",
    ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk",
]


def local_ip() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"


def main() -> None:
    apk = next((p for p in APK_CANDIDATES if p.exists()), None)
    if not apk:
        print("APK not found. Build it first with: .\\gradlew.bat assembleRelease", file=sys.stderr)
        sys.exit(1)

    ip = local_ip()
    serve_dir = apk.parent
    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(serve_dir))
    socketserver.TCPServer.allow_reuse_address = True

    with socketserver.TCPServer(("0.0.0.0", PORT), handler) as httpd:
        url = f"http://{ip}:{PORT}/{apk.name}"
        print(f"Serving {apk} ({apk.stat().st_size / (1024 * 1024):.2f} MB)")
        print(f"On Android TV (Downloader app or browser), open:\n  {url}")
        print("Press Ctrl+C to stop.")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nStopped.")


if __name__ == "__main__":
    main()
