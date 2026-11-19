import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import socket
import threading
import unittest

from controlled_proxy import ControlledProxy


class Target(BaseHTTPRequestHandler):
    def do_POST(self):
        self.rfile.read(int(self.headers.get('Content-Length', '0')))
        self.send_response(200)
        self.send_header('Content-Length', '2')
        self.end_headers()
        self.wfile.write(b'ok')

    def log_message(self, *args):
        pass


class ProxyTest(unittest.TestCase):
    def test_pass_through_records_status_without_headers(self):
        target = ThreadingHTTPServer(('127.0.0.1', 0), Target)
        threading.Thread(target=target.serve_forever, daemon=True).start()
        proxy = ControlledProxy(target.server_port)
        proxy.start()
        try:
            conn = http.client.HTTPConnection('127.0.0.1', proxy.port, timeout=5)
            conn.request('POST', '/api/v1/payments', b'{}', {'X-Secret': 'not-recorded'})
            response = conn.getresponse()
            self.assertEqual(200, response.status)
            self.assertEqual(11, response.version)
            self.assertEqual('close', response.getheader('Connection'))
            response.read()
            conn.close()
            for _ in range(100):
                if proxy.captured_events(): break
                threading.Event().wait(.01)
            event = proxy.captured_events()[0]
            self.assertEqual(200, event['upstream_status'])
            self.assertEqual(200, event['downstream_status'])
            self.assertNotIn('not-recorded', str(event))
        finally:
            proxy.stop()
            target.shutdown()
            target.server_close()

    def test_refused_upstream_closes_the_caller_connection(self):
        with socket.socket() as unused:
            unused.bind(('127.0.0.1', 0))
            closed_port = unused.getsockname()[1]
        proxy = ControlledProxy(closed_port)
        proxy.start()
        try:
            conn = http.client.HTTPConnection('127.0.0.1', proxy.port, timeout=3)
            conn.request('GET', '/api/v1/payments/key')
            with self.assertRaises((http.client.RemoteDisconnected, ConnectionResetError)):
                conn.getresponse()
            conn.close()
        finally:
            proxy.stop()

    def test_holds_response_after_target_received_request(self):
        target = ThreadingHTTPServer(('127.0.0.1', 0), Target)
        threading.Thread(target=target.serve_forever, daemon=True).start()
        proxy = ControlledProxy(target.server_port)
        proxy.start()
        try:
            proxy.arm('after')
            def call():
                conn = http.client.HTTPConnection('127.0.0.1', proxy.port, timeout=5)
                try:
                    conn.request('POST', '/api/v1/payments', b'{}')
                    conn.getresponse().read()
                except (OSError, http.client.HTTPException):
                    pass
            caller = threading.Thread(target=call)
            caller.start()
            self.assertTrue(proxy.reached.wait(3))
            self.assertTrue(caller.is_alive())
            self.assertEqual(1, proxy.active_count())
            proxy.release.set()
            caller.join(3)
            self.assertFalse(caller.is_alive())
            self.assertEqual(0, proxy.active_count())
        finally:
            proxy.stop()
            target.shutdown()
            target.server_close()


if __name__ == '__main__':
    unittest.main()
