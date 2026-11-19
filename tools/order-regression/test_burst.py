import asyncio
import unittest

from burst import run_burst


class BurstTest(unittest.IsolatedAsyncioTestCase):
    async def test_releases_all_attempts_and_keeps_failures(self):
        starts = []

        async def send(attempt):
            starts.append(attempt['id'])
            if attempt['id'] == 'b':
                raise OSError('unreachable')
            return {'status': 201}

        result = await run_burst([{'id': 'a'}, {'id': 'b'}, {'id': 'c'}], send)
        self.assertEqual(3, result['barrier_arrived'])
        self.assertCountEqual(['a', 'b', 'c'], starts)
        self.assertEqual({'a', 'b', 'c'}, {r['id'] for r in result['attempts']})
        self.assertEqual(1, sum('error' in r for r in result['attempts']))

    async def test_empty_burst(self):
        result = await run_burst([], lambda _: None)
        self.assertEqual(0, result['barrier_arrived'])
        self.assertEqual([], result['attempts'])


if __name__ == '__main__':
    unittest.main()
