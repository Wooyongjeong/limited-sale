import unittest
from evidence import intermediate_errors


class IntermediateTest(unittest.TestCase):
    def test_pending_orders_keep_reservations_for_both_scenarios(self):
        rows = [{'public_id': 'a', 'order_status': 'PAYMENT_PENDING', 'reservation_status': 'RESERVED'},
                {'public_id': 'b', 'order_status': 'PAYMENT_PENDING', 'reservation_status': 'RESERVED'}]
        scenarios = [{'order_public_id': 'a', 'scenario': 'DELAY'},
                     {'order_public_id': 'b', 'scenario': 'RESPONSE_LOSS'}]
        self.assertEqual([], intermediate_errors(rows, scenarios))
        self.assertTrue(intermediate_errors(rows[:1], scenarios))
        bad_rows = rows + [{'public_id': 'c', 'order_status': 'INVALID', 'reservation_status': 'RESERVED'}]
        bad_scenarios = scenarios + [{'order_public_id': 'c', 'scenario': 'DELAY'}]
        self.assertTrue(intermediate_errors(bad_rows, bad_scenarios))
        rows[0]['reservation_status'] = 'RELEASED'
        self.assertTrue(intermediate_errors(rows, scenarios))
        rows[0]['reservation_status'] = 'RESERVED'
        rows[0]['order_status'] = 'INVALID'
        self.assertTrue(intermediate_errors(rows, scenarios))
        rows[0]['order_status'] = 'PAYMENT_PENDING'
        rows[1]['order_status'] = 'CONFIRMED'
        rows[1]['reservation_status'] = 'CONFIRMED'
        self.assertTrue(intermediate_errors(rows, scenarios))


if __name__ == '__main__':
    unittest.main()
