import copy
import json
from pathlib import Path
import tempfile
import unittest

from audit_case import audit_case
from clock_guard import record
from test_verify import fixture


class CaseAuditTest(unittest.TestCase):
    def test_raw_http_order_and_key_must_match_snapshot(self):
        document = fixture()
        document['manifest']['recovery_required'] = False
        document['recovery'] = None
        document['orders'][0]['public_id'] = 'public'
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            def save(name, data):
                (path / name).write_text(json.dumps(data))
            raw = {'barrier_arrived': 1, 'attempts': [{'id': 'a1', 'write_completed': True,
                    'response': {'http_status': 201, 'body': {'code': 'ORDER_CREATED', 'data': {'orderId': 'public'}}}}]}
            payments = [{'order_public_id': 'public', 'http_status': 200, 'body': {'code': 'PAYMENT_SUCCEEDED'}}]
            before = [{'order_id': 1, 'pg_key': 'pg', 'amount': 10}]
            save('snapshot.json', document)
            save('order-attempts.json', raw)
            save('payment-attempts.json', payments)
            save('payment-identities-before.json', before)
            save('clock-guard.json', record({'wall': 0, 'monotonic': 0}, {'wall': 9, 'monotonic': 9}))
            self.assertEqual([], audit_case(path))
            corrupted = copy.deepcopy(raw)
            corrupted['attempts'][0]['response']['body']['data']['orderId'] = 'other'
            save('order-attempts.json', corrupted)
            self.assertTrue(audit_case(path))
            save('order-attempts.json', raw)
            before[0]['pg_key'] = 'different'
            save('payment-identities-before.json', before)
            self.assertTrue(audit_case(path))
            before[0]['pg_key'] = 'pg'
            save('payment-identities-before.json', before)
            self.assertEqual([], audit_case(path))
            save('clock-guard.json', record({'wall': 0, 'monotonic': 0}, {'wall': 900, 'monotonic': 9}))
            self.assertTrue(audit_case(path))
            (path / 'clock-guard.json').unlink()
            with self.assertRaises(OSError):
                audit_case(path)


if __name__ == '__main__':
    unittest.main()
