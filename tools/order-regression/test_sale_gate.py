import unittest
from sale_gate import wait_open


class SaleGateTest(unittest.IsolatedAsyncioTestCase):
    async def test_waits_for_database_clock_to_enter_sale_window(self):
        samples = iter([{'open': 0, 'db_now': 'before'}, {'open': 1, 'db_now': 'during'}])
        result = await wait_open(lambda: next(samples), timeout=1, interval=.001)
        self.assertEqual('during', result['db_now'])

    async def test_times_out_instead_of_sending_early(self):
        with self.assertRaises(TimeoutError):
            await wait_open(lambda: {'open': 0}, timeout=.01, interval=.001)


if __name__ == '__main__':
    unittest.main()
