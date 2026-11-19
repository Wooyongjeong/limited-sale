import unittest

from evidence import match_public_order


class EvidenceTest(unittest.TestCase):
    def test_matches_http_order_against_stored_public_id(self):
        response = {'data': {'orderId': 'abcd'}}
        self.assertTrue(match_public_order(response, {'public_id': 'abcd'}))
        self.assertFalse(match_public_order(response, {'public_id': 'dcba'}))
        self.assertFalse(match_public_order(response, None))

class PaymentKeyTest(unittest.TestCase):
    def test_fixed_key_amount_and_all_orders_are_preserved(self):
        from evidence import fixed_payment_errors
        before = [{'order_id': 1, 'pg_key': 'one', 'amount': 10}]
        self.assertEqual([], fixed_payment_errors(before, [{'order_id': 1, 'pg_key': 'one', 'amount': 10}]))
        self.assertTrue(fixed_payment_errors(before, [{'order_id': 1, 'pg_key': 'two', 'amount': 10}]))
        self.assertTrue(fixed_payment_errors(before, [{'order_id': 1, 'pg_key': 'one', 'amount': 20}]))
        self.assertTrue(fixed_payment_errors(before, []))


if __name__ == '__main__':
    unittest.main()
