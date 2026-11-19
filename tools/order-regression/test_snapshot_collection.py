import json
import re
import unittest

from snapshot import collect


class FakeEnv:
    def __init__(self, empty=()):
        self.calls = []
        self.empty = empty

    def mysql(self, which, sql):
        self.calls.append((which, sql))
        if which == 'pg':
            return json.dumps({'pg_key': 'key'})
        lines = []
        for section in re.findall(r"SELECT '\{\"section\":\"(\w+)\"\}';", sql):
            lines.append(json.dumps({'section': section}))
            if section not in self.empty:
                lines.append(json.dumps({'from': section}))
        return '\n'.join(lines)


class SnapshotCollectionTest(unittest.TestCase):
    def test_sales_tables_are_read_from_one_consistent_snapshot(self):
        env = FakeEnv()
        collect(env, 1)
        sales_calls = [sql for which, sql in env.calls if which == 'sales']
        self.assertEqual(1, len(sales_calls))
        self.assertIn('REPEATABLE READ', sales_calls[0])
        self.assertLess(sales_calls[0].index('START TRANSACTION WITH CONSISTENT SNAPSHOT'),
                        sales_calls[0].index('FROM sales'))

    def test_rows_stay_in_their_own_section(self):
        snapshot = collect(FakeEnv(empty=('buyers',)), 1)
        self.assertEqual([{'from': 'sales'}], snapshot['sales'])
        self.assertEqual([], snapshot['buyers'])
        self.assertEqual([{'from': 'orders'}], snapshot['orders'])
        self.assertEqual([{'from': 'request_results'}], snapshot['request_results'])
        self.assertEqual([{'pg_key': 'key'}], snapshot['pg_payments'])


if __name__ == '__main__':
    unittest.main()
