import unittest
from evidence import quiescence_errors


class QuiescenceTest(unittest.TestCase):
    def test_equal_snapshots_do_not_hide_in_flight_pg_calls(self):
        before = {'snapshot': {'sales': [1]}, 'active_pg_requests': 0, 'started_ns': 100, 'observed_ns': 110}
        after = {'snapshot': {'sales': [1]}, 'active_pg_requests': 0, 'started_ns': 200, 'observed_ns': 210}
        self.assertEqual([], quiescence_errors(before, after, []))
        invalid = dict(after, started_ns=90)
        self.assertTrue(quiescence_errors(before, invalid, []))
        self.assertTrue(quiescence_errors(before, after, [{'started_ns': 90, 'finished_ns': 150}]))
        self.assertTrue(quiescence_errors(before, after, [{'started_ns': 205, 'finished_ns': 220}]))
        after['snapshot'] = {'sales': [2]}
        self.assertTrue(quiescence_errors(before, after, []))


if __name__ == '__main__':
    unittest.main()
