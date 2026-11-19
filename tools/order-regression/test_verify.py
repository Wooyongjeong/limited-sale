import copy
import unittest

from verify import verify


def fixture():
    attempt = {'attempt_id': 'a1', 'member_id': 1, 'key': 'request', 'sale_id': 1,
               'quantity': 1, 'allowed_results': ['CREATED'], 'rejection_reason': None,
               'final_statuses': ['CONFIRMED']}
    return {
        'final': True,
        'manifest': {'sales': [{'id': 1, 'unit_price': 10, 'total_quantity': 3, 'per_member_limit': 1}],
                     'attempts': [attempt], 'recovery_required': True, 'expected_results': {'CREATED': 1},
                     'expected_order_statuses': {'CONFIRMED': 1}},
        'recovery': {'deadline_seconds': 120, 'end_elapsed_seconds': 10,
                     'targets': [{'order_id': 1, 'elapsed_seconds': 9}]},
        'requests': [{'attempt_id': 'a1', 'member_id': 1, 'key': 'request', 'sale_id': 1,
                      'quantity': 1, 'sent': True, 'result': 'CREATED', 'order_id': 1,
                      'rejection_reason': None}],
        'request_results': [{'member_id': 1, 'key': 'request', 'sale_id': 1, 'quantity': 1,
                             'outcome': 'CREATED', 'order_id': 1, 'rejection_reason': None}],
        'sales': [{'id': 1, 'unit_price': 10, 'total_quantity': 3, 'reserved_quantity': 0,
                   'sold_quantity': 1, 'per_member_limit': 1}],
        'buyers': [{'sale_id': 1, 'member_id': 1, 'reserved_quantity': 0, 'purchased_quantity': 1}],
        'orders': [{'id': 1, 'member_id': 1, 'key': 'request', 'sale_id': 1, 'quantity': 1,
                    'unit_price': 10, 'total_amount': 10, 'status': 'CONFIRMED'}],
        'reservations': [{'order_id': 1, 'sale_id': 1, 'member_id': 1, 'quantity': 1,
                          'status': 'CONFIRMED'}],
        'payments': [{'order_id': 1, 'pg_key': 'pg', 'amount': 10, 'status': 'SUCCEEDED'}],
        'pg_payments': [{'pg_key': 'pg', 'amount': 10, 'status': 'SUCCEEDED'}],
    }


def add_attempt(data, attempt_id, result, **changes):
    planned = copy.deepcopy(data['manifest']['attempts'][0])
    actual = copy.deepcopy(data['requests'][0])
    planned.update(attempt_id=attempt_id, allowed_results=[result])
    actual.update(attempt_id=attempt_id, result=result)
    for key, value in changes.items():
        if key in planned:
            planned[key] = value
        if key in actual:
            actual[key] = value
    if result != 'CREATED':
        planned['final_statuses'] = None
        actual['order_id'] = None
    data['manifest']['attempts'].append(planned)
    data['requests'].append(actual)
    data['manifest']['expected_results'][result] = data['manifest']['expected_results'].get(result, 0) + 1


class VerifyTest(unittest.TestCase):
    def test_valid_final_snapshot(self):
        self.assertEqual([], verify(fixture()))

    def test_same_key_replay_and_changed_input_conflict(self):
        data = fixture()
        add_attempt(data, 'retry', 'CREATED')
        add_attempt(data, 'conflict', 'CONFLICT', quantity=2)
        self.assertEqual([], verify(data))
        data['requests'][2]['quantity'] = 1
        data['manifest']['attempts'][2]['quantity'] = 1
        self.assertTrue(verify(data))

    def test_rejection_reason_and_replay_are_checked(self):
        data = fixture()
        add_attempt(data, 'rejected', 'REJECTED', member_id=2, key='r', rejection_reason='SALE_SOLD_OUT')
        data['request_results'].append({'member_id': 2, 'key': 'r', 'sale_id': 1, 'quantity': 1,
                                        'outcome': 'REJECTED', 'order_id': None,
                                        'rejection_reason': 'SALE_SOLD_OUT'})
        add_attempt(data, 'retry', 'REJECTED', member_id=2, key='r', rejection_reason='SALE_SOLD_OUT')
        self.assertEqual([], verify(data))
        data['request_results'][1]['rejection_reason'] = 'PURCHASE_LIMIT_EXCEEDED'
        self.assertTrue(verify(data))

    def test_race_dependent_counts_and_reasons_accept_a_planned_range(self):
        data = fixture()
        add_attempt(data, 'rejected', 'REJECTED', member_id=2, key='r',
                    rejection_reason=['SALE_SOLD_OUT', 'PURCHASE_LIMIT_EXCEEDED'])
        data['request_results'].append({'member_id': 2, 'key': 'r', 'sale_id': 1, 'quantity': 1,
                                        'outcome': 'REJECTED', 'order_id': None,
                                        'rejection_reason': 'PURCHASE_LIMIT_EXCEEDED'})
        data['requests'][1]['rejection_reason'] = 'PURCHASE_LIMIT_EXCEEDED'
        data['manifest']['expected_results'] = {'CREATED': [1, 2], 'REJECTED': [0, 1]}
        self.assertEqual([], verify(data))
        data['requests'][1]['rejection_reason'] = 'SALE_NOT_FOUND'
        data['request_results'][1]['rejection_reason'] = 'SALE_NOT_FOUND'
        self.assertTrue(verify(data))
        data['requests'][1]['rejection_reason'] = 'SALE_SOLD_OUT'
        data['request_results'][1]['rejection_reason'] = 'SALE_SOLD_OUT'
        self.assertEqual([], verify(data))
        data['manifest']['expected_results']['CREATED'] = [2, 3]
        self.assertTrue(verify(data))

    def test_corrupt_snapshots_fail(self):
        mutations = {
            'sale count': lambda x: x['sales'][0].update(sold_quantity=0),
            'buyer count': lambda x: x['buyers'][0].update(purchased_quantity=0),
            'reservation': lambda x: x['reservations'][0].update(status='RELEASED'),
            'payment amount': lambda x: x['payments'][0].update(amount=11),
            'pg status': lambda x: x['pg_payments'][0].update(status='FAILED'),
            'all prices wrong': lambda x: (x['sales'][0].update(unit_price=20),
                x['orders'][0].update(unit_price=20, total_amount=20), x['payments'][0].update(amount=20),
                x['pg_payments'][0].update(amount=20)),
            'not sent': lambda x: x['requests'][0].update(sent=False),
            'server error': lambda x: x['requests'][0].update(result='ERROR'),
            'missing attempt': lambda x: x['requests'].clear(),
            'duplicate attempt': lambda x: x['requests'].append(copy.deepcopy(x['requests'][0])),
            'changed identity': lambda x: x['requests'][0].update(member_id=2),
            'result input': lambda x: x['request_results'][0].update(quantity=2),
            'status counts': lambda x: x['manifest']['expected_order_statuses'].update(CONFIRMED=2),
            'result counts': lambda x: x['manifest']['expected_results'].update(CREATED=2),
            'late last snapshot': lambda x: x['recovery'].update(end_elapsed_seconds=121),
            'late target': lambda x: x['recovery']['targets'][0].update(elapsed_seconds=121),
            'missing target': lambda x: x['recovery']['targets'].clear(),
            'unknown target': lambda x: x['recovery']['targets'][0].update(elapsed_seconds=None),
            'missing required recovery': lambda x: x.update(recovery=None),
            'orphan buyer': lambda x: x['buyers'].append({'sale_id': 1, 'member_id': 2,
                'reserved_quantity': 0, 'purchased_quantity': 0}),
            'duplicate target': lambda x: x['recovery']['targets'].append(copy.deepcopy(x['recovery']['targets'][0])),
            'missing result': lambda x: x['request_results'].clear(),
            'duplicate payment': lambda x: x['payments'].append(copy.deepcopy(x['payments'][0])),
            'missing pg': lambda x: x['pg_payments'].clear(),
            'extra pg': lambda x: x['pg_payments'].append({'pg_key': 'extra', 'amount': 10, 'status': 'SUCCEEDED'}),
            'extra rejected result': lambda x: x['request_results'].append({'member_id': 2, 'key': 'extra',
                'sale_id': 1, 'quantity': 1, 'outcome': 'REJECTED', 'order_id': None,
                'rejection_reason': 'SALE_SOLD_OUT'}),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label):
                data = fixture()
                mutate(data)
                self.assertTrue(verify(data))

    def test_failed_order_releases_and_another_user_reuses_stock(self):
        data = fixture()
        data['orders'][0]['status'] = 'PAYMENT_FAILED'
        data['reservations'][0]['status'] = 'RELEASED'
        data['buyers'][0]['purchased_quantity'] = 0
        data['payments'][0]['status'] = 'FAILED'
        data['pg_payments'][0]['status'] = 'FAILED'
        data['manifest']['attempts'][0]['final_statuses'] = ['PAYMENT_FAILED']
        add_attempt(data, 'reuse', 'CREATED', member_id=2, key='new')
        data['requests'][1]['order_id'] = 2
        data['manifest']['attempts'][1]['final_statuses'] = ['CONFIRMED']
        data['manifest']['expected_order_statuses'] = {'PAYMENT_FAILED': 1, 'CONFIRMED': 1}
        data['request_results'].append({'member_id': 2, 'key': 'new', 'sale_id': 1, 'quantity': 1,
                                        'outcome': 'CREATED', 'order_id': 2, 'rejection_reason': None})
        data['orders'].append({'id': 2, 'member_id': 2, 'key': 'new', 'sale_id': 1, 'quantity': 1,
                               'unit_price': 10, 'total_amount': 10, 'status': 'CONFIRMED'})
        data['reservations'].append({'order_id': 2, 'sale_id': 1, 'member_id': 2, 'quantity': 1,
                                     'status': 'CONFIRMED'})
        data['buyers'].append({'sale_id': 1, 'member_id': 2, 'reserved_quantity': 0, 'purchased_quantity': 1})
        data['payments'].append({'order_id': 2, 'pg_key': 'pg2', 'amount': 10, 'status': 'SUCCEEDED'})
        data['pg_payments'].append({'pg_key': 'pg2', 'amount': 10, 'status': 'SUCCEEDED'})
        data['recovery']['targets'].append({'order_id': 2, 'elapsed_seconds': 10})
        self.assertEqual([], verify(data))
        data['reservations'][0]['status'] = 'RESERVED'
        self.assertTrue(verify(data))

    def test_intermediate_allows_pending_but_not_final(self):
        data = fixture()
        data['orders'][0]['status'] = 'PAYMENT_PENDING'
        data['reservations'][0]['status'] = 'RESERVED'
        data['sales'][0].update(reserved_quantity=1, sold_quantity=0)
        data['buyers'][0].update(reserved_quantity=1, purchased_quantity=0)
        data['manifest']['expected_order_statuses'] = {'PAYMENT_PENDING': 1}
        self.assertTrue(verify(data))
        data['final'] = False
        data['manifest']['recovery_required'] = False
        data['recovery'] = None
        self.assertEqual([], verify(data))


if __name__ == '__main__':
    unittest.main()
