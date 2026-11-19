import json
from pathlib import Path
import tempfile
import unittest
from audit_forcekill import audit_boundary, audit_clock
from clock_guard import record


class ForceKillAuditTest(unittest.TestCase):
    def test_detects_absent_lease_wrong_boundary_and_missing_kill(self):
        boundary = {'at': '2026-01-01T00:00:01+00:00', 'profile_users': 100, 'profile_stock': 30, 'sales_pid': 42,
                    'state': {'lease_held': 1, 'order_status': 'PAYMENT_PENDING',
                              'payment_status': 'PROCESSING', 'pg_key': 'key'},
                    'pg': [], 'proxy': {'stage': 'before_forward'},
                    'data_lock_waits': 0, 'sale_lock_connection': None}
        execution = {'kill_at': '2026-01-01T00:00:02+00:00', 'pid': 42, 'exit_code': -9, 'payment_request': {'error': 'IncompleteReadError'},
                     'terminal_elapsed_seconds': 32}
        final = {'payments': [{'order_id': 1, 'pg_key': 'key'}],
                 'pg_payments': [{'pg_key': 'key', 'status': 'SUCCEEDED'}]}
        self.assertEqual([], audit_boundary('lease', boundary, execution, final))
        execution['kill_at'] = '2026-01-01T00:00:00+00:00'
        self.assertTrue(audit_boundary('lease', boundary, execution, final))
        execution['kill_at'] = '2026-01-01T00:00:02+00:00'
        boundary['state']['lease_held'] = 0
        self.assertTrue(audit_boundary('lease', boundary, execution, final))
        boundary['state']['lease_held'] = 1
        execution['exit_code'] = 0
        self.assertTrue(audit_boundary('lease', boundary, execution, final))
        execution['exit_code'] = -9
        self.assertTrue(audit_boundary('pg_success', boundary, execution, final))

    def test_clock_evidence_must_exist_and_show_no_suspend(self):
        with tempfile.TemporaryDirectory() as tmp:
            case = Path(tmp)
            with self.assertRaises(OSError):
                audit_clock(case)
            (case / 'clock-guard.json').write_text(json.dumps(record({'wall': 0, 'monotonic': 0}, {'wall': 40, 'monotonic': 39})))
            self.assertEqual([], audit_clock(case))
            (case / 'clock-guard.json').write_text(json.dumps(record({'wall': 0, 'monotonic': 0}, {'wall': 40, 'monotonic': 20})))
            self.assertTrue(audit_clock(case))
            (case / 'clock-guard.json').write_text('{}')
            self.assertTrue(audit_clock(case))


if __name__ == '__main__':
    unittest.main()
