import asyncio
import unittest

from http_client import Client, TruncatedResponse


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

    async def test_request_full_returns_headers_and_keeps_the_status_of_a_non_json_body(self):
        async def serve(reader, writer):
            await reader.readuntil(b'\r\n\r\n')
            body = b'<html>bad gateway</html>' if self.proxy_error else b'{"code":"ORDER_RATE_LIMITED"}'
            writer.write(b'HTTP/1.1 ' + (b'502 Bad Gateway' if self.proxy_error else b'429 Too Many Requests') +
                         b'\r\nRetry-After: 2\r\nContent-Length: ' + str(len(body)).encode() +
                         b'\r\nConnection: close\r\n\r\n' + body)
            await writer.drain()
            writer.close()
        server = await asyncio.start_server(serve, '127.0.0.1', 0)
        async with server:
            client = Client(server.sockets[0].getsockname()[1])
            self.proxy_error = False
            status, body, headers = await client.request_full('POST', '/orders')
            self.assertEqual((429, {'code': 'ORDER_RATE_LIMITED'}, '2'), (status, body, headers['retry-after']))
            self.proxy_error = True
            status, body, _ = await client.request_full('POST', '/orders')
            self.assertEqual((502, None), (status, body))
            with self.assertRaises(ValueError):
                await client.request('POST', '/orders')

    async def test_a_body_cut_off_after_the_headers_keeps_the_status_for_the_record(self):
        async def serve(reader, writer):
            await reader.readuntil(b'\r\n\r\n')
            writer.write(b'HTTP/1.1 409 Conflict\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n')
            await writer.drain()
            writer.close()
        server = await asyncio.start_server(serve, '127.0.0.1', 0)
        async with server:
            client = Client(server.sockets[0].getsockname()[1])
            with self.assertRaises(TruncatedResponse) as caught:
                await client.request_full('POST', '/orders')
            self.assertEqual(409, caught.exception.status)


if __name__ == '__main__':
    unittest.main()
