import unittest

from load_summary import (SCENARIOS, Scenario, aggregate, build_manifest, build_requests, evaluate, failures,
                          headline)
from verify import verify


def run(**changes):
    """A passing baseline result; each test changes the one field it targets."""
    k6 = {'exit_code': 0, 'iterations_completed': 10000, 'dropped_iterations': 0, 'server_errors_5xx': 0,
          'connection_errors': 0, 'status_counts': {'201': 1000, '409': 9000, '200': 1000},
          'business_codes': {'ORDER_CREATED': 1000, 'SALE_SOLD_OUT': 9000, 'PAYMENT_SUCCEEDED': 1000},
          'http_requests_per_second': 900.0, 'iterations_per_second': 700.0, 'duration_seconds': 14.0,
          'latency': {'order_create': {'p50_ms': 10, 'p95_ms': 20, 'p99_ms': 30, 'avg_ms': 12, 'max_ms': 99},
                      'order_payment': {'p50_ms': 50, 'p95_ms': 80, 'p99_ms': 90, 'avg_ms': 55, 'max_ms': 120}}}
    result = {'k6': k6, 'db': {'orders': 1000, 'confirmed': 1000, 'remaining': 0, 'converged': True},
              'verifier_violations': 0, 'sql_violations': 0, 'mysql_observe_complete': True,
              'order_create_by_instance': {'sales1': 5000, 'sales2': 5000}}
    for key, value in changes.items():
        section, _, field = key.partition('__')
        if field:
            result[section][field] = value
        else:
            result[key] = value
    return result


def names(checks):
    return [c['name'] for c in checks if not c['passed']]


class EvaluateTest(unittest.TestCase):
    def test_policy_scenarios(self):
        self.assertEqual(SCENARIOS['baseline'], Scenario(users=10000, stock=1000, vus=500))
        self.assertEqual(SCENARIOS['under-demand'], Scenario(users=800, stock=1000, vus=500))

    def test_passing_baseline_has_no_failures(self):
        self.assertEqual(failures(evaluate(run(), SCENARIOS['baseline'])), [])

    def test_each_policy_violation_is_reported(self):
        base = SCENARIOS['baseline']
        cases = {
            'server_errors_5xx': run(k6__server_errors_5xx=1),
            'connection_errors': run(k6__connection_errors=2),
            'confirmed': run(db__confirmed=999),
            'remaining': run(db__remaining=1),
            'verifier_violations': run(verifier_violations=1),
            'reconciliation_violations': run(sql_violations=3),
            'converged': run(db__converged=False),
            'k6_exit_code': run(k6__exit_code=99),
            'dropped_iterations': run(k6__dropped_iterations=5),
            'iterations_completed': run(k6__iterations_completed=9999),
            'mysql_observe_complete': run(mysql_observe_complete=False),
        }
        for name, result in cases.items():
            with self.subTest(name):
                self.assertIn(name, names(evaluate(result, base)))

    def test_business_409_is_not_an_error_but_other_statuses_are(self):
        base = SCENARIOS['baseline']
        self.assertEqual(names(evaluate(run(), base)), [])
        bad = run(k6__status_counts={'201': 1000, '409': 8999, '401': 1, '200': 1000})
        self.assertIn('unexpected_statuses', names(evaluate(bad, base)))

    def test_client_and_database_views_must_agree(self):
        base = SCENARIOS['baseline']
        lost = run(db__orders=1001)
        self.assertIn('client_created_matches_orders', names(evaluate(lost, base)))
        unpaid = run(k6__business_codes={'ORDER_CREATED': 1000, 'SALE_SOLD_OUT': 9000, 'PAYMENT_SUCCEEDED': 990})
        self.assertIn('payments_requested_for_every_created_order', names(evaluate(unpaid, base)))
        missing = run(order_create_by_instance={'sales1': 5000, 'sales2': 4999})
        self.assertIn('every_order_request_reached_an_instance', names(evaluate(missing, base)))
        idle = run(order_create_by_instance={'sales1': 10000, 'sales2': 0})
        self.assertIn('every_instance_served_orders', names(evaluate(idle, base)))

    def test_under_demand_expects_all_users_confirmed_and_stock_left(self):
        scenario = SCENARIOS['under-demand']
        result = run(k6__iterations_completed=800, db__orders=800, db__confirmed=800, db__remaining=200,
                     k6__business_codes={'ORDER_CREATED': 800, 'PAYMENT_SUCCEEDED': 800},
                     order_create_by_instance={'sales1': 400, 'sales2': 400})
        self.assertEqual(failures(evaluate(result, scenario)), [])
        sold_out = run(k6__iterations_completed=800, db__orders=800, db__confirmed=800, db__remaining=200,
                       k6__business_codes={'ORDER_CREATED': 800, 'SALE_SOLD_OUT': 1, 'PAYMENT_SUCCEEDED': 800},
                       order_create_by_instance={'sales1': 400, 'sales2': 400})
        self.assertIn('client_rejected_matches_expected', names(evaluate(sold_out, scenario)))


class VerifierInputTest(unittest.TestCase):
    ATTEMPTS = [{'id': f'a{i}', 'client': i, 'member_id': 100 + i, 'key': f'k{i}'} for i in range(3)]

    def test_manifest_expects_stock_created_and_the_rest_rejected(self):
        manifest = build_manifest(self.ATTEMPTS, 7, Scenario(users=3, stock=2, vus=3))
        self.assertEqual(manifest['expected_results'], {'CREATED': 2, 'REJECTED': 1})
        self.assertEqual(manifest['expected_order_statuses'], {'CONFIRMED': 2})
        self.assertEqual({a['attempt_id'] for a in manifest['attempts']}, {'a0', 'a1', 'a2'})
        self.assertEqual(manifest['sales'][0]['total_quantity'], 2)

    def test_requests_come_from_stored_results_and_missing_ones_are_unsent(self):
        stored = [{'member_id': 100, 'key': 'k0', 'outcome': 'CREATED', 'order_id': 55, 'rejection_reason': None},
                  {'member_id': 101, 'key': 'k1', 'outcome': 'REJECTED', 'order_id': None,
                   'rejection_reason': 'SALE_SOLD_OUT'}]
        requests = build_requests(self.ATTEMPTS, 7, stored)
        self.assertEqual([r['result'] for r in requests], ['CREATED', 'REJECTED', 'ERROR'])
        self.assertEqual([r['sent'] for r in requests], [True, True, False])
        self.assertEqual(requests[0]['order_id'], 55)
        self.assertEqual(requests[1]['rejection_reason'], 'SALE_SOLD_OUT')

    def test_verifier_flags_an_attempt_without_a_stored_result(self):
        scenario = Scenario(users=1, stock=1, vus=1)
        attempts = self.ATTEMPTS[:1]
        data = {'final': True, 'manifest': build_manifest(attempts, 7, scenario),
                'requests': build_requests(attempts, 7, []),
                'sales': [{'id': 7, 'unit_price': 1000, 'total_quantity': 1, 'reserved_quantity': 0,
                           'sold_quantity': 0, 'per_member_limit': 1}],
                'buyers': [], 'orders': [], 'reservations': [], 'payments': [], 'request_results': [],
                'pg_payments': [], 'recovery': None}
        errors = verify(data)
        self.assertIn('attempt a0: not sent', errors)


class AggregateTest(unittest.TestCase):
    def summary(self, rps, p99, locks):
        result = run(k6__http_requests_per_second=rps)
        result['k6']['latency']['order_create']['p99_ms'] = p99
        management = {'combined': {'hikari': {'active_peak': 10, 'pending_peak': 5, 'acquire_mean_ms': 40.0,
                                              'acquire_max_ms_peak': None},
                                   'jvm': {'process_cpu_usage_peak': 0.5, 'system_cpu_usage_peak': 0.9,
                                           'system_cpu_usage_mean': 0.7, 'cpu_seconds': 60.0,
                                           'gc_pause_seconds': 0.1, 'tomcat_threads_busy_peak': None}}}
        return {**result, 'management': management, 'load_generator': {'cpu_seconds': 90.0, 'cpu_cores_mean': 3.2},
                'mysql': {'row_lock_waits': locks, 'row_lock_time_ms': locks * 10, 'deadlocks': 0,
                          'lock_timeouts': 0},
                'durations_seconds': {'total_seconds': 100.0}}

    def test_headline_flattens_numbers_and_drops_missing_values(self):
        flat = headline(self.summary(900.0, 30, 7))
        self.assertEqual(flat['throughput.http_requests_per_second'], 900.0)
        self.assertEqual(flat['latency_ms.order_create.p99_ms'], 30)
        self.assertEqual(flat['status_counts.409'], 9000)
        self.assertEqual(flat['business_codes.SALE_SOLD_OUT'], 9000)
        self.assertEqual(flat['mysql.row_lock_waits'], 7)
        self.assertEqual(flat['management.hikari_pending_peak'], 5)
        self.assertEqual(flat['management.jvm_cpu_seconds'], 60.0)
        self.assertEqual(flat['load_generator.cpu_cores_mean'], 3.2)
        self.assertNotIn('management.hikari_acquire_max_ms_peak', flat)

    def test_median_min_max_over_runs(self):
        metrics = aggregate([self.summary(900.0, 30, 7), self.summary(1000.0, 50, 9), self.summary(800.0, 40, 8)])
        rate = metrics['throughput.http_requests_per_second']
        self.assertEqual((rate['median'], rate['min'], rate['max']), (900.0, 800.0, 1000.0))
        self.assertEqual(rate['values'], [900.0, 1000.0, 800.0])
        self.assertEqual(metrics['latency_ms.order_create.p99_ms']['median'], 40)

    def test_a_single_run_aggregates_to_itself(self):
        metrics = aggregate([self.summary(900.0, 30, 7)])
        self.assertEqual(metrics['mysql.row_lock_waits'], {'median': 7, 'min': 7, 'max': 7, 'values': [7]})


if __name__ == '__main__':
    unittest.main()
