"""Small asynchronous HTTP/1.1 client for isolated loopback experiments."""
import asyncio
from http.cookies import SimpleCookie
import json


class Client:
    def __init__(self, port):
        self.port = port
        self.cookies = {}
        self.csrf = None

    async def request(self, method, path, body=None, headers=None, timeout=35, on_sent=None):
        return await asyncio.wait_for(self._request(method, path, body, headers, on_sent), timeout)

    async def _request(self, method, path, body, headers, on_sent):
        reader, writer = await asyncio.open_connection('127.0.0.1', self.port)
        try:
            data = b'' if body is None else json.dumps(body, separators=(',', ':')).encode()
            fields = {'Host': f'127.0.0.1:{self.port}', 'Connection': 'close',
                      'Content-Length': str(len(data))}
            if body is not None:
                fields['Content-Type'] = 'application/json'
            if self.cookies:
                fields['Cookie'] = '; '.join(f'{k}={v}' for k, v in self.cookies.items())
            if self.csrf and method not in ('GET', 'HEAD'):
                fields['X-CSRF-TOKEN'] = self.csrf
            fields.update(headers or {})
            request = f'{method} {path} HTTP/1.1\r\n' + ''.join(f'{k}: {v}\r\n' for k, v in fields.items()) + '\r\n'
            writer.write(request.encode('ascii') + data)
            await writer.drain()
            if on_sent is not None:
                on_sent()
            header = (await reader.readuntil(b'\r\n\r\n')).decode('iso-8859-1').split('\r\n')
            status = int(header[0].split()[1])
            response_headers = {}
            for line in header[1:]:
                if not line:
                    continue
                name, value = line.split(':', 1)
                if name.lower() == 'set-cookie':
                    cookie = SimpleCookie()
                    cookie.load(value.strip())
                    self.cookies.update({key: morsel.value for key, morsel in cookie.items()})
                else:
                    response_headers[name.lower()] = value.strip()
            if response_headers.get('transfer-encoding', '').lower() == 'chunked':
                chunks = []
                while True:
                    size = int((await reader.readline()).split(b';', 1)[0], 16)
                    if not size:
                        break
                    chunks.append(await reader.readexactly(size))
                    await reader.readexactly(2)
                raw = b''.join(chunks)
            elif 'content-length' in response_headers:
                raw = await reader.readexactly(int(response_headers['content-length']))
            else:
                raw = await reader.read()
            return status, json.loads(raw) if raw else None
        finally:
            writer.close()
            await writer.wait_closed()

    async def get_csrf(self):
        status, response = await self.request('GET', '/api/v1/csrf')
        if status != 200:
            raise RuntimeError(f'CSRF failed with HTTP {status}')
        self.csrf = response['data']['token']

    async def login(self, email, password):
        await self.get_csrf()
        status, _ = await self.request('POST', '/api/v1/sessions', {'email': email, 'password': password})
        if status != 200:
            raise RuntimeError(f'login failed with HTTP {status}')
        await self.get_csrf()
