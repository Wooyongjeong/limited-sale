"""Loopback-only PG proxy that can hold one selected payment request or response."""
import http.client
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
import time


class ControlledProxy:
    def __init__(self, target_port):
        self.target_port = target_port
        self.reached = threading.Event()
        self.release = threading.Event()
        self.mode = None
        self.observation = None
        self.lock = threading.Lock()
        self.active = 0
        self.events = []
        owner = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'

            def log_message(self, *args):
                pass

            def do_GET(self):
                self.forward()

            def do_PUT(self):
                self.forward()

            def do_POST(self):
                self.forward()

            def forward(self):
                event = {'method': self.command, 'path': self.path, 'started_ns': time.monotonic_ns()}
                with owner.lock:
                    owner.active += 1
                try:
                    self.forward_tracked(event)
                finally:
                    event['finished_ns'] = time.monotonic_ns()
                    with owner.lock:
                        owner.active -= 1
                        owner.events.append(event)

            def forward_tracked(self, event):
                body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
                selected = self.command == 'POST' and self.path == '/api/v1/payments' and owner.mode is not None
                if selected and owner.mode == 'before':
                    owner.observation = {'stage': 'before_forward', 'monotonic_ns': time.monotonic_ns()}
                    owner.reached.set()
                    owner.release.wait(15)
                    self.close_connection = True
                    return
                connection = http.client.HTTPConnection('127.0.0.1', owner.target_port, timeout=10)
                phase = 'upstream'
                try:
                    headers = {k: v for k, v in self.headers.items() if k.lower() not in ('host', 'connection')}
                    connection.request(self.command, self.path, body, headers)
                    upstream = connection.getresponse()
                    event['upstream_status'] = upstream.status
                    payload = upstream.read()
                    try:
                        decoded = json.loads(payload)
                        data = decoded.get('data') or {}
                        event['response_code'] = decoded.get('code')
                        event['scenario'] = data.get('scenario') if isinstance(data, dict) else None
                        event['delay_seconds'] = data.get('delaySeconds') if isinstance(data, dict) else None
                    except (ValueError, TypeError, AttributeError):
                        event['response_json_valid'] = False
                    if selected and owner.mode == 'after':
                        owner.observation = {'stage': 'after_upstream', 'http_status': upstream.status,
                                             'monotonic_ns': time.monotonic_ns()}
                        owner.reached.set()
                        owner.release.wait(15)
                        self.close_connection = True
                        return
                    phase = 'downstream'
                    self.send_response(upstream.status)
                    self.send_header('Content-Type', upstream.getheader('Content-Type', 'application/json'))
                    self.send_header('Content-Length', str(len(payload)))
                    self.send_header('Connection', 'close')
                    self.close_connection = True
                    self.end_headers()
                    self.wfile.write(payload)
                    self.wfile.flush()
                    event['downstream_status'] = upstream.status
                except (OSError, http.client.HTTPException) as exc:
                    event['error'] = type(exc).__name__
                    event['error_phase'] = phase
                    self.close_connection = True
                finally:
                    connection.close()
        class ProxyServer(ThreadingHTTPServer):
            request_queue_size = 1024

        self.server = ProxyServer(('127.0.0.1', 0), Handler)
        self.server.daemon_threads = True
        self.port = self.server.server_port
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def active_count(self):
        with self.lock:
            return self.active

    def captured_events(self):
        with self.lock:
            return list(self.events)

    def start(self):
        self.thread.start()

    def arm(self, mode):
        self.mode = mode
        self.observation = None
        self.reached.clear()
        self.release.clear()

    def disarm(self):
        self.mode = None
        self.release.set()

    def stop(self):
        self.disarm()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
