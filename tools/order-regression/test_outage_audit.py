import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from audit_outage import audit, audit_clock, audit_logs, audit_recovery
from clock_guard import record


class OutageAuditTest(unittest.TestCase):
    def test_recovery_requires_timely_convergence_and_valid_snapshot(self):
        with patch('audit_outage.verify', return_value=[]):
            self.assertEqual([], audit_recovery({'terminal_elapsed_seconds': 9.1}, {}))
            self.assertTrue(audit_recovery({'terminal_elapsed_seconds': None}, {}))
            self.assertTrue(audit_recovery({'terminal_elapsed_seconds': 120.1}, {}))
        with patch('audit_outage.verify', return_value=['order 1: not final']):
            self.assertEqual(['order 1: not final'], audit_recovery({'terminal_elapsed_seconds': 9.1}, {}))

    def fixture(self):
        return [
            {'elapsed_seconds': 2, 'row': {'expires_at': '2026-01-01 00:05:00', 'requires_review': 0,
                 'reservation': 'RESERVED', 'reserved_quantity': 1, 'status': 'PAYMENT_PENDING',
                 'next_review_warning_at': None, 'last_checked_at': '2026-01-01 00:00:00'}},
            {'elapsed_seconds': 310, 'row': {'expires_at': '2026-01-01 00:05:00', 'requires_review': 1,
                 'reservation': 'RESERVED', 'reserved_quantity': 1, 'status': 'PAYMENT_PENDING',
                 'next_review_warning_at': '2026-01-01 00:10:02', 'last_checked_at': '2026-01-01 00:05:02'}},
            {'elapsed_seconds': 640, 'row': {'expires_at': '2026-01-01 00:05:00', 'requires_review': 1,
                 'reservation': 'RESERVED', 'reserved_quantity': 1, 'status': 'PAYMENT_PENDING',
                 'next_review_warning_at': '2026-01-01 00:15:02', 'last_checked_at': '2026-01-01 00:10:02'}}]

    def test_warning_logs_have_correct_wall_clock_cadence(self):
        expiry = '2026-01-01 00:05:00'
        good = ['2026-01-01T00:05:01+00:00 WARN review', '2026-01-01T00:10:02+00:00 WARN review']
        self.assertEqual([], audit_logs(good, expiry))
        self.assertTrue(audit_logs(good[:1], expiry))
        self.assertTrue(audit_logs([good[0], '2026-01-01T00:09:00+00:00 WARN review'], expiry))

    def test_gap_is_reported_when_observation_is_missing(self):
        self.assertTrue(any('sample gap' in x for x in audit(self.fixture())))

    def test_db_warning_schedule_and_reservation(self):
        self.assertEqual(['sample gap exceeds 30 seconds'], audit(self.fixture()))
        truncated = self.fixture()[:-1]
        self.assertTrue(any('coverage' in x for x in audit(truncated)))
        bad = self.fixture()
        bad[-1]['row']['next_review_warning_at'] = '2026-01-01 00:14:59'
        self.assertGreater(len(audit(bad)), 1)
        bad = self.fixture()
        bad[-1]['row']['reservation'] = 'RELEASED'
        self.assertGreater(len(audit(bad)), 1)
        bad = self.fixture()
        bad[1]['row']['last_checked_at'] = '2026-01-01 00:00:05'
        self.assertGreater(len(audit(bad)), 1)

    def test_clock_evidence_must_exist_and_show_no_suspend(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            with self.assertRaises(OSError):
                audit_clock(path)
            (path / 'clock-guard.json').write_text(json.dumps(record({'wall': 0, 'monotonic': 0}, {'wall': 700, 'monotonic': 700})))
            self.assertEqual([], audit_clock(path))
            (path / 'clock-guard.json').write_text(json.dumps(record({'wall': 0, 'monotonic': 0}, {'wall': 1380, 'monotonic': 101})))
            self.assertTrue(audit_clock(path))
            (path / 'clock-guard.json').write_text('[]')
            self.assertTrue(audit_clock(path))


if __name__ == '__main__':
    unittest.main()
