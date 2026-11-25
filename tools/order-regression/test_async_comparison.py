"""Pure logic of the stage 3-8 v2 comparison: inversion counts, the acceptance summary and the v2 pass criteria."""
import unittest

from comparison import (CONDITIONS, Plan, async_db_summary, count_inversions, evaluate, evaluate_async, failures,
                        percentile, policy_plan)
from test_comparison import passing


def acceptance(identifier, status, finished, reason=None, consumed=True, orders=None, sends=1, completion=100.0):
    return {'id': identifier, 'status': status, 'rejection_reason': reason, 'failure_reason': None,
            'finished_at_us': finished, 'accepted_at_us': 0, 'completion_ms': completion, 'consumed': consumed,
            'orders': (1 if status == 'ORDER_CREATED' else 0) if orders is None else orders, 'sends': sends}


def endpoint(count=0, statuses=None, codes=None):
    return {'count': count, 'status_counts': statuses or {}, 'business_codes': codes or {}}


def result(**overrides):
    plan_users, stock = 100, 10
    store = async_db_summary([acceptance(i, 'ORDER_CREATED', i) for i in range(stock)]
                             + [acceptance(stock + i, 'REJECTED', stock + i, 'SALE_SOLD_OUT', consumed=False)
                                for i in range(plan_users - stock)])
    value = {
        'k6': {'exit_code': 0, 'iterations_completed': plan_users, 'dropped_iterations': 0, 'server_errors_5xx': 0,
               'connection_errors': 0, 'unresolved_requests': 0,
               'finals': {'ORDER_CREATED': stock},
               'endpoints': {'order_create': endpoint(plan_users, {'202': stock, '409': plan_users - stock}),
                             'order_status': endpoint(30, {'200': 30}),
                             'order_payment': endpoint(stock, {'200': stock}, {'PAYMENT_SUCCEEDED': stock})}},
        'db': {'orders': stock, 'confirmed': stock, 'remaining': 0, 'converged': True},
        'async': store, 'all_final_within_deadline': True, 'sql_violations': 0, 'mysql_observe_complete': True,
        'cache_metrics_present': True, 'gate_metrics_present': True,
        'gate': {'hit': 80, 'miss': 20, 'error': 0},
        'order_create_by_instance': {'sales1': 50, 'sales2': 50}, 'completion_recorded': stock,
    }
    value.update(overrides)
    return value


PLAN = Plan(users=100, stock=10, vus=10, detail_views=0, abusers=0)


class InversionTest(unittest.TestCase):
    def test_counts_every_pair_out_of_order(self):
        self.assertEqual(count_inversions([]), 0)
        self.assertEqual(count_inversions([1, 2, 3]), 0)
        self.assertEqual(count_inversions([3, 2, 1]), 3)
        self.assertEqual(count_inversions([1, 3, 2, 5, 4, 0]), 7)
        # Equal finish times are not inversions.
        self.assertEqual(count_inversions([2, 2, 1]), 2)

    def test_large_input_matches_the_quadratic_definition(self):
        values = [(i * 7919) % 1009 for i in range(1500)]
        slow = sum(1 for i in range(len(values)) for j in range(i + 1, len(values)) if values[i] > values[j])
        self.assertEqual(count_inversions(values), slow)

    def test_nearest_rank_percentile(self):
        self.assertIsNone(percentile([], 0.5))
        self.assertEqual(percentile(list(range(1, 11)), 0.5), 5)
        self.assertEqual(percentile(list(range(1, 11)), 0.95), 10)
        self.assertEqual(percentile(list(range(1, 101)), 0.99), 99)


class SummaryTest(unittest.TestCase):
    def test_orders_inversions_and_requests_that_lost_to_a_later_one(self):
        rows = [acceptance(1, 'ORDER_CREATED', 10), acceptance(2, 'REJECTED', 5, 'SALE_SOLD_OUT'),
                acceptance(3, 'ORDER_CREATED', 20, sends=2), acceptance(4, 'REJECTED', 30, 'SALE_SOLD_OUT'),
                acceptance(5, 'REJECTED', 1, 'SALE_SOLD_OUT', consumed=False, completion=0.0),
                acceptance(6, 'ACCEPTED', None, completion=None)]
        store = async_db_summary(rows)
        self.assertEqual(store['acceptances'], 6)
        self.assertEqual(store['consumed'], 4)
        self.assertEqual(store['unfinished'], 1)
        # Finish order by id 1..4 is 10, 5, 20, 30: one inversion. Acceptance 2 was sold out before 3 got its order.
        self.assertEqual(store['order_inversions'], 1)
        self.assertEqual(store['sold_out_before_a_later_order'], 1)
        self.assertEqual(store['republished_acceptances'], 1)
        self.assertEqual(store['completion_ms']['count'], 2)
        self.assertEqual(store['reasons'], {'REJECTED:SALE_SOLD_OUT': 3})

    def test_more_than_one_order_for_an_acceptance_is_counted(self):
        store = async_db_summary([acceptance(1, 'ORDER_CREATED', 1, orders=2)])
        self.assertEqual(store['acceptances_with_more_than_one_order'], 1)


class EvaluateTest(unittest.TestCase):
    def test_a_clean_run_passes(self):
        self.assertEqual(failures(evaluate_async(result(), CONDITIONS['v2-async'], PLAN)), [])

    def test_unfinished_failed_or_duplicated_acceptances_fail(self):
        store = result()['async'] | {'unfinished': 1, 'processing_failed': 2, 'acceptances_with_more_than_one_order': 1}
        names = {c['name'] for c in evaluate_async(result(**{'async': store, 'all_final_within_deadline': False}),
                                                   CONDITIONS['v2-async'], PLAN) if not c['passed']}
        self.assertTrue({'no_unfinished_acceptances', 'no_processing_failures', 'at_most_one_order_per_acceptance',
                         'all_final_within_deadline'} <= names)

    def test_a_5xx_fails_a_normal_run_but_not_a_fault_run_where_it_was_answered_on_retry(self):
        k6 = result()['k6'] | {'server_errors_5xx': 3}
        k6['endpoints'] = dict(k6['endpoints'], order_status=endpoint(33, {'200': 30, '502': 3}))
        normal = {c['name'] for c in evaluate_async(result(k6=k6), CONDITIONS['v2-async'], PLAN) if not c['passed']}
        self.assertIn('server_errors_5xx', normal)
        self.assertIn('unexpected_statuses', normal)
        fault = failures(evaluate_async(result(k6=k6), CONDITIONS['v2-async'], PLAN, fault=object()))
        self.assertEqual(fault, [])

    def test_a_fault_run_accepts_a_pending_payment_answer(self):
        k6 = result()['k6']
        k6['endpoints'] = dict(k6['endpoints'], order_payment=endpoint(10, {'200': 9, '202': 1},
                                                                       {'PAYMENT_SUCCEEDED': 9, 'PAYMENT_PENDING': 1}))
        self.assertIn('every_order_paid: expected 10 actual 9',
                      failures(evaluate_async(result(k6=k6), CONDITIONS['v2-async'], PLAN)))
        self.assertEqual(failures(evaluate_async(result(k6=k6), CONDITIONS['v2-async'], PLAN, fault=object())), [])

    def test_the_server_histogram_must_have_seen_every_consumed_acceptance(self):
        self.assertIn('completion_histogram_matches_database: expected 10 actual 9',
                      failures(evaluate_async(result(completion_recorded=9), CONDITIONS['v2-async'], PLAN)))

    def test_the_v1_condition_is_judged_like_c_on(self):
        condition = CONDITIONS['v1-sync']
        self.assertEqual(failures(evaluate(passing('c-on'), condition, policy_plan(condition))), [])

    def test_both_run_the_c_on_settings_at_the_policy_plan(self):
        from run_comparison import sales_extra_env, settings_record
        for name in ('v1-sync', 'v2-async'):
            condition = CONDITIONS[name]
            self.assertEqual(policy_plan(condition), Plan(10000, 1000, 500, 0, 0), name)
            self.assertEqual(sales_extra_env(condition), sales_extra_env(CONDITIONS['c-on']), name)
            record = settings_record(condition)
            self.assertEqual((record['sale_cache_enabled'], record['order_rate_limit_enabled'],
                              record['sold_out_gate_enabled'], record['order_rate_limit_max_requests']),
                             (True, True, True, 1), name)

    def test_v1_and_v2_conditions_differ_only_in_the_api(self):
        v1, v2 = CONDITIONS['v1-sync'], CONDITIONS['v2-async']
        self.assertEqual(v1.api, 'v1')
        self.assertEqual(v2.api, 'v2')
        self.assertEqual({k: v for k, v in v1.__dict__.items() if k not in ('name', 'api')},
                         {k: v for k, v in v2.__dict__.items() if k not in ('name', 'api')})


if __name__ == '__main__':
    unittest.main()
