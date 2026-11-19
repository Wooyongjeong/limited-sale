import unittest
from evidence import payment_errors


class PaymentEvidenceTest(unittest.TestCase):
    def test_errors_cannot_be_treated_as_business_outcomes(self):
        success = {'http_status': 200, 'body': {'code': 'PAYMENT_SUCCEEDED'}}
        self.assertEqual([], payment_errors([success], {'PAYMENT_SUCCEEDED'}, 1))
        self.assertTrue(payment_errors([{'error': 'TimeoutError'}], {'PAYMENT_SUCCEEDED'}, 1))
        self.assertTrue(payment_errors([{'http_status': 500, 'body': {'code': 'INTERNAL_ERROR'}}], {'PAYMENT_SUCCEEDED'}, 1))
        self.assertTrue(payment_errors([], {'PAYMENT_SUCCEEDED'}, 1))


if __name__ == '__main__':
    unittest.main()
