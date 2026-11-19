#!/usr/bin/env python3
"""Stress the experimental proxy with Java HTTP client requests using no credentials."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import subprocess
import sys
import threading

from controlled_proxy import ControlledProxy


class Target(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_PUT(self):
        self.rfile.read(int(self.headers.get('Content-Length', '0')))
        payload = b'{"code":"OK","data":{"scenario":"DELAY","delaySeconds":10}}'
        self.send_response(200)
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


if __name__ == '__main__':
    server = ThreadingHTTPServer(('127.0.0.1', 0), Target)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    proxy = ControlledProxy(server.server_port)
    proxy.start()
    try:
        java = str(Path(os.environ['JAVA_HOME']) / 'bin/java')
        result = subprocess.run([java, str(Path(__file__).with_name('ProxyProbe.java')), str(proxy.port)],
                                text=True, capture_output=True, timeout=120)
        print(result.stdout, end='')
        events = proxy.captured_events()
        errors = sum('error' in event for event in events)
        print(f'proxy handled {len(events)} requests, {errors} upstream/downstream errors')
        sys.exit(0 if result.returncode == 0 and result.stdout.strip() == '3000 OK'
                 and len(events) == 3000 and errors == 0 else 1)
    finally:
        proxy.stop()
        server.shutdown()
        server.server_close()
