import asyncio
import unittest

from http_client import Client


class HttpClientTest(unittest.IsolatedAsyncioTestCase):
    async def test_cookie_and_json_response(self):
        async def serve(reader, writer):
            request = await reader.readuntil(b'\r\n\r\n')
            self.assertIn(b'GET /test HTTP/1.1', request)
            writer.write(b'HTTP/1.1 200 OK\r\nSet-Cookie: SESSION=secret; Path=/\r\n'
                         b'Content-Length: 11\r\nConnection: close\r\n\r\n{"ok":true}')
            await writer.drain()
            writer.close()
        server = await asyncio.start_server(serve, '127.0.0.1', 0)
        async with server:
            client = Client(server.sockets[0].getsockname()[1])
            sent = []
            status, body = await client.request('GET', '/test', on_sent=lambda: sent.append(True))
            self.assertEqual([True], sent)
            self.assertEqual((200, {'ok': True}), (status, body))
            self.assertEqual('secret', client.cookies['SESSION'])


if __name__ == '__main__':
    unittest.main()
