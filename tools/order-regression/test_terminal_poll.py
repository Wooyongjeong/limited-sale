import unittest
from evidence import terminal_poll_errors


class TerminalPollTest(unittest.TestCase):
    def test_all_targets_terminal_and_idle_before_snapshot(self):
        polls = [{'elapsed_seconds': 10, 'active_pg_requests': 0,
                  'targets': [{'id': 1, 'status': 'CONFIRMED'}]}]
        self.assertEqual([], terminal_poll_errors(polls, {1}, 120))
        self.assertTrue(terminal_poll_errors(polls, {1, 2}, 120))
        polls[0]['active_pg_requests'] = 1
        self.assertTrue(terminal_poll_errors(polls, {1}, 120))
        polls[0]['active_pg_requests'] = 0
        polls[0]['elapsed_seconds'] = 121
        self.assertTrue(terminal_poll_errors(polls, {1}, 120))


if __name__ == '__main__':
    unittest.main()
