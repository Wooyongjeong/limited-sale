import unittest

from comparison import (CONDITIONS, DEFAULT_CONDITIONS, Plan, abuse_attempts, abuse_server_summary, aggregate, build_manifest, cache_counts,
                        derived, evaluate, execution_order, failures, headline, hit_rate, labeled_deltas, policy_plan,
                        rate_limit_counts, verifier_requests, with_overrides)
from load_summary import build_requests
from verify import verify


def endpoint(count=0, statuses=None, codes=None, **latency):
    return {'count': count, 'status_counts': statuses or {}, 'business_codes': codes or {},
            'p50_ms': 1, 'p95_ms': 2, 'p99_ms': 3, 'avg_ms': 1.5, 'max_ms': 9, **latency}


def k6_a():
    return {'exit_code': 0, 'iterations_completed': 10000, 'dropped_iterations': 0, 'server_errors_5xx': 0,
            'connection_errors': 0, 'duration_seconds': 40.0, 'normal_duration_seconds': 40.0,
            'http_requests_per_second': 2000.0,
            'endpoints': {
                'order_create': endpoint(10000, {'201': 1000, '409': 9000},
                                         {'ORDER_CREATED': 1000, 'SALE_SOLD_OUT': 9000}),
                'order_payment': endpoint(1000, {'200': 1000}, {'PAYMENT_SUCCEEDED': 1000}),
                'sale_detail': endpoint(50000, {'200': 50000}, {'SALE_FOUND': 50000}),
                'abuse_order_create': endpoint(), 'abuse_order_payment': endpoint()}}


def k6_b(rate_limited, vus_per_member=1):
    abuse = {'201': 40, '409': 3000, **({'429': 60000} if rate_limited else {})}
    abuse_requests = sum(abuse.values())
    return {'exit_code': 0, 'iterations_completed': 10000, 'dropped_iterations': 0, 'server_errors_5xx': 0,
            'abuser_vus_per_member': vus_per_member,
            'connection_errors': 0, 'duration_seconds': 50.0, 'normal_duration_seconds': 49.0,
            'http_requests_per_second': 3000.0,
            'endpoints': {
                'order_create': endpoint(10000, {'201': 960, '409': 9040}, {'ORDER_CREATED': 960, 'SALE_SOLD_OUT': 9040}),
                'order_payment': endpoint(960, {'200': 960}, {'PAYMENT_SUCCEEDED': 960}),
                'sale_detail': endpoint(),
                'abuse_order_create': endpoint(abuse_requests, abuse,
                                               {'ORDER_CREATED': 40, 'SALE_SOLD_OUT': 1500,
                                                'PURCHASE_LIMIT_EXCEEDED': 1500,
                                                **({'ORDER_RATE_LIMITED': 60000} if rate_limited else {})}),
                'abuse_order_payment': endpoint(40, {'200': 40}, {'PAYMENT_SUCCEEDED': 40})}}


NO_LIMIT = {'create': {'allowed': 0, 'rejected': 0, 'bypassed': 0},
            'payment': {'allowed': 0, 'rejected': 0, 'bypassed': 0}}


def passing(name):
    condition = CONDITIONS[name]
    if condition.experiment == 'A':
        return {'cache_metrics_present': condition.sale_cache, 'k6': k6_a(), 'db': {'orders': 1000, 'confirmed': 1000, 'remaining': 0, 'converged': True,
                                     'request_results': 10000},
                'verifier_violations': 0, 'sql_violations': 0, 'mysql_observe_complete': True,
                'order_create_by_instance': {'sales1': 5000, 'sales2': 5000},
                'abuse': {'stored_requests': 0, 'created': 0, 'rejections': {}, 'confirmed': 0},
                'rate_limit': NO_LIMIT,
                'cache': {'hit': 0, 'miss': 0, 'error': 0} if not condition.sale_cache
                else {'hit': 49990, 'miss': 10, 'error': 0}}
    limited = condition.order_rate_limit
    k6 = k6_b(limited, condition.abuser_vus_per_member)
    abuse_total = k6['endpoints']['abuse_order_create']['count']
    create_requests = 10000 + abuse_total
    rate_limit = NO_LIMIT
    if limited:
        rate_limit = {'create': {'allowed': create_requests - 60000, 'rejected': 60000, 'bypassed': 0},
                      'payment': {'allowed': 1000, 'rejected': 0, 'bypassed': 0}}
    return {'cache_metrics_present': True, 'k6': k6, 'db': {'orders': 1000, 'confirmed': 1000, 'remaining': 0,
                                                              'converged': True,
                             'request_results': create_requests - (60000 if limited else 0)},
            'verifier_violations': 0, 'sql_violations': 0, 'mysql_observe_complete': True,
            'order_create_by_instance': {'sales1': create_requests // 2, 'sales2': create_requests - create_requests // 2},
            'abuse': {'stored_requests': abuse_total - (60000 if limited else 0), 'created': 40,
                      'rejections': {'SALE_SOLD_OUT': 1500, 'PURCHASE_LIMIT_EXCEEDED': 1500}, 'confirmed': 40},
            'rate_limit': rate_limit, 'cache': {'hit': 0, 'miss': 0, 'error': 0}}


def names(checks):
    return [c['name'] for c in checks if not c['passed']]


def change(result, path, value):
    target = result
    *parents, last = path.split('.')
    for part in parents:
        target = target[part]
    target[last] = value
    return result


class ConditionsTest(unittest.TestCase):
    def test_policy_conditions(self):
        self.assertEqual({name: (c.sale_cache, c.order_rate_limit, c.detail_views, c.abusers, c.abuser_vus_per_member)
                          for name, c in CONDITIONS.items()},
                         {'a-off': (False, False, 5, 0, 1), 'a-on': (True, False, 5, 0, 1),
                          'b-off': (True, False, 0, 100, 1), 'b-on': (True, True, 0, 100, 1),
                          'b2-off': (True, False, 0, 100, 5), 'b2-on': (True, True, 0, 100, 5)})
        self.assertEqual(DEFAULT_CONDITIONS, ('a-off', 'a-on', 'b-off', 'b-on'))
        self.assertEqual(policy_plan(CONDITIONS['b2-on']), Plan(10000, 1000, 500, 0, 100, 5))
        self.assertEqual(policy_plan(CONDITIONS['a-on']), Plan(10000, 1000, 500, 5, 0))
        self.assertEqual(policy_plan(CONDITIONS['b-off']), Plan(10000, 1000, 500, 0, 100))

    def test_overrides_never_add_reads_or_abusers_to_the_other_experiment(self):
        a = with_overrides(policy_plan(CONDITIONS['a-off']), users=500, stock=100, vus=50, detail_views=2, abusers=9)
        self.assertEqual(a, Plan(500, 100, 50, 2, 0))
        b = with_overrides(policy_plan(CONDITIONS['b-on']), users=500, stock=100, vus=50, detail_views=2, abusers=10)
        self.assertEqual(b, Plan(500, 100, 50, 0, 10))
        self.assertEqual(with_overrides(policy_plan(CONDITIONS['b-on'])), policy_plan(CONDITIONS['b-on']))
        smoke = with_overrides(policy_plan(CONDITIONS['b2-on']), abusers=10, abuser_vus_per_member=3)
        self.assertEqual((smoke.abusers, smoke.abuser_vus_per_member), (10, 3))
        a = with_overrides(policy_plan(CONDITIONS['a-on']), abuser_vus_per_member=3)
        self.assertEqual(a.abuser_vus_per_member, 1)

    def test_execution_order_interleaves_the_conditions_in_every_round(self):
        order = execution_order(['a-off', 'a-on', 'b-off', 'b-on'], 3)
        self.assertEqual(len(order), 12)
        self.assertEqual(order[:5], [(1, 'a-off'), (1, 'a-on'), (1, 'b-off'), (1, 'b-on'), (2, 'a-off')])
        self.assertEqual(order[-1], (3, 'b-on'))

    def test_expected_confirmed_counts_every_user_up_to_the_stock(self):
        self.assertEqual(Plan(10000, 1000, 500, 5, 0).expected_confirmed, 1000)
        self.assertEqual(Plan(800, 1000, 500, 0, 100).expected_confirmed, 900)
        self.assertEqual(Plan(500, 100, 50, 0, 10).expected_confirmed, 100)


class EvaluateTest(unittest.TestCase):
    def test_every_condition_passes_when_nothing_is_wrong(self):
        for name, condition in CONDITIONS.items():
            with self.subTest(name):
                self.assertEqual(failures(evaluate(passing(name), condition, policy_plan(condition))), [])

    def test_policy_violations_fail_in_every_condition(self):
        for name, condition in CONDITIONS.items():
            plan = policy_plan(condition)
            cases = {
                'server_errors_5xx': ('k6.server_errors_5xx', 1),
                'connection_errors': ('k6.connection_errors', 2),
                'confirmed': ('db.confirmed', 999),
                'remaining': ('db.remaining', 1),
                'verifier_violations': ('verifier_violations', 1),
                'reconciliation_violations': ('sql_violations', 3),
                'converged': ('db.converged', False),
                'k6_exit_code': ('k6.exit_code', 99),
                'mysql_observe_complete': ('mysql_observe_complete', False),
            }
            for failed, (path, value) in cases.items():
                with self.subTest(name=name, check=failed):
                    result = change(passing(name), path, value)
                    self.assertIn(failed, names(evaluate(result, condition, plan)))

    def test_business_429_and_409_are_not_errors_but_other_statuses_are_even_for_abusers(self):
        condition = CONDITIONS['b-on']
        plan = policy_plan(condition)
        self.assertEqual(names(evaluate(passing('b-on'), condition, plan)), [])
        result = passing('b-on')
        result['k6']['endpoints']['abuse_order_create']['status_counts']['401'] = 1
        self.assertIn('unexpected_statuses', names(evaluate(result, condition, plan)))
        result = passing('a-on')
        result['k6']['endpoints']['sale_detail']['status_counts']['404'] = 2
        self.assertIn('unexpected_statuses', names(evaluate(result, CONDITIONS['a-on'], policy_plan(CONDITIONS['a-on']))))

    def test_client_and_server_views_must_agree(self):
        condition, plan = CONDITIONS['b-on'], policy_plan(CONDITIONS['b-on'])
        lost = change(passing('b-on'), 'db.orders', 1001)
        self.assertIn('client_created_matches_orders', names(evaluate(lost, condition, plan)))
        recorded = change(passing('b-on'), 'abuse.stored_requests', 1)
        self.assertIn('abuse_server_records_match_client', names(evaluate(recorded, condition, plan)))
        unpaid = change(passing('b-on'), 'k6.endpoints.abuse_order_payment.count', 39)
        self.assertIn('payments_requested_for_every_created_order', names(evaluate(unpaid, condition, plan)))
        silent = change(passing('b-on'), 'order_create_by_instance', {'sales1': 10, 'sales2': 10})
        self.assertIn('every_order_request_reached_an_instance', names(evaluate(silent, condition, plan)))
        idle = change(passing('b-on'), 'order_create_by_instance', {'sales1': 10000 + 63040, 'sales2': 0})
        self.assertIn('every_instance_served_orders', names(evaluate(idle, condition, plan)))

    def test_rate_limited_requests_must_not_be_recorded_by_the_server(self):
        condition, plan = CONDITIONS['b-on'], policy_plan(CONDITIONS['b-on'])
        recorded = passing('b-on')
        recorded['db']['request_results'] += 1
        self.assertIn('server_stored_every_create_that_was_not_rate_limited', names(evaluate(recorded, condition, plan)))

    def test_rate_limit_counters_follow_the_switch(self):
        on, off = CONDITIONS['b-on'], CONDITIONS['b-off']
        counted = change(passing('b-off'), 'rate_limit.create.allowed', 5)
        self.assertIn('rate_limit_off_counts_nothing', names(evaluate(counted, off, policy_plan(off))))
        mismatched = change(passing('b-on'), 'rate_limit.create.rejected', 59999)
        broken = names(evaluate(mismatched, on, policy_plan(on)))
        self.assertIn('client_429_matches_server_rejections', broken)
        self.assertIn('every_create_was_counted_by_the_limit', broken)
        bypassed = change(passing('b-on'), 'rate_limit.create.bypassed', 1)
        self.assertIn('rate_limit_bypassed', names(evaluate(bypassed, on, policy_plan(on))))

    def test_cache_counters_follow_the_switch(self):
        off, on = CONDITIONS['a-off'], CONDITIONS['a-on']
        used = change(passing('a-off'), 'cache.hit', 1)
        self.assertIn('no_cache_lookups', names(evaluate(used, off, policy_plan(off))))
        skipped = change(passing('a-on'), 'cache.hit', 10)
        self.assertIn('every_detail_read_was_a_cache_lookup', names(evaluate(skipped, on, policy_plan(on))))
        failing = passing('a-on')
        failing['cache'] = {'hit': 49989, 'miss': 10, 'error': 1}
        self.assertIn('cache_errors', names(evaluate(failing, on, policy_plan(on))))

    def test_a_cache_switch_that_did_not_reach_the_jvms_fails(self):
        for name in ('a-off', 'a-on'):
            condition = CONDITIONS[name]
            result = change(passing(name), 'cache_metrics_present', not condition.sale_cache)
            with self.subTest(name):
                self.assertIn('sale_cache_setting_took_effect',
                              names(evaluate(result, condition, policy_plan(condition))))

    def test_missing_detail_reads_fail_experiment_a(self):
        on = CONDITIONS['a-on']
        result = change(passing('a-on'), 'k6.endpoints.sale_detail.status_counts', {'200': 49999})
        self.assertIn('detail_reads_all_200', names(evaluate(result, on, policy_plan(on))))

    def test_b2_conditions_pass_with_five_vus_per_member_and_fail_if_k6_ran_another_number(self):
        for name in ('b2-off', 'b2-on'):
            condition = CONDITIONS[name]
            plan = policy_plan(condition)
            ran = passing(name)
            self.assertEqual(ran['k6']['abuser_vus_per_member'], 5)
            self.assertEqual(names(evaluate(ran, condition, plan)), [], name)
            ran['k6']['abuser_vus_per_member'] = 1
            self.assertIn('abuser_vus_per_member_as_planned', names(evaluate(ran, condition, plan)), name)

    def test_abusers_must_actually_run_in_experiment_b(self):
        on = CONDITIONS['b-on']
        result = change(passing('b-on'), 'k6.endpoints.abuse_order_create', endpoint())
        self.assertIn('abusers_ran_only_when_planned', names(evaluate(result, on, policy_plan(on))))


class CountersTest(unittest.TestCase):
    def test_labeled_deltas_and_summaries(self):
        before = {('order_rate_limit_total', (('operation', 'create'), ('result', 'rejected'))): 2.0,
                  ('order_rate_limit_total', (('operation', 'create'), ('result', 'allowed'))): 1.0,
                  ('other_total', ()): 9.0}
        after = {**before,
                 ('order_rate_limit_total', (('operation', 'create'), ('result', 'rejected'))): 12.0,
                 ('order_rate_limit_total', (('operation', 'payment'), ('result', 'allowed'))): 4.0}
        second = {('order_rate_limit_total', (('operation', 'create'), ('result', 'rejected'))): 5.0}
        counts = rate_limit_counts([labeled_deltas(before, after, 'order_rate_limit_total'),
                                    labeled_deltas({}, second, 'order_rate_limit_total')])
        self.assertEqual(counts, {'create': {'allowed': 0, 'rejected': 15, 'bypassed': 0},
                                  'payment': {'allowed': 4, 'rejected': 0, 'bypassed': 0}})

    def test_cache_counts_and_hit_rate_leave_errors_out_of_the_denominator(self):
        series = lambda result, value: {('sale_cache_requests_total', (('result', result),)): value}
        merged = cache_counts([labeled_deltas({}, series('hit', 90.0) | series('miss', 8.0) | series('error', 2.0),
                                              'sale_cache_requests_total')])
        self.assertEqual(merged, {'hit': 90, 'miss': 8, 'error': 2})
        self.assertEqual(hit_rate(merged), round(90 / 98, 4))
        self.assertIsNone(hit_rate({'hit': 0, 'miss': 0, 'error': 0}))

    def test_derived_ratios(self):
        k6 = k6_b(True)
        db = {'confirmed': 1000}
        cache = {'hit': 0, 'miss': 0, 'error': 0}
        limit = {'create': {'allowed': 3000, 'rejected': 60000, 'bypassed': 0}}
        values = derived(k6, db, cache, limit, 100)
        self.assertEqual(values['abuse_requests_per_member_per_second'], round(63040 / 100 / 49.0, 3))
        self.assertIsNone(derived(k6, db, cache, limit)['abuse_requests_per_member_per_second'])
        self.assertEqual(values['actual_order_throughput_per_second'], 20.0)
        self.assertEqual(values['actual_order_throughput_per_normal_second'], round(1000 / 49.0, 3))
        self.assertEqual(values['abuse_rejection_rate'], round(60000 / 63040, 4))
        self.assertEqual(values['create_rejection_rate_by_server'], round(60000 / 63000, 4))
        self.assertIsNone(values['cache_hit_rate'])


class AbuseVerifierInputTest(unittest.TestCase):
    """verify.py accepts abusive attempts that exist only as the server's stored request results."""

    def snapshot(self, abuse_results, orders_status='CONFIRMED'):
        plan = Plan(users=4, stock=2, vus=2, detail_views=0, abusers=2)
        normal = [{'id': f'n{i}', 'member_id': 100 + i, 'key': f'key-n{i}'} for i in range(4)]
        abuser_ids = {200, 201}
        results = [
            {'member_id': 100, 'key': 'key-n0', 'sale_id': 1, 'quantity': 1, 'outcome': 'CREATED', 'order_id': 1,
             'rejection_reason': None},
            {'member_id': 101, 'key': 'key-n1', 'sale_id': 1, 'quantity': 1, 'outcome': 'REJECTED', 'order_id': None,
             'rejection_reason': 'SALE_SOLD_OUT'},
            {'member_id': 102, 'key': 'key-n2', 'sale_id': 1, 'quantity': 1, 'outcome': 'REJECTED', 'order_id': None,
             'rejection_reason': 'SALE_SOLD_OUT'},
            {'member_id': 103, 'key': 'key-n3', 'sale_id': 1, 'quantity': 1, 'outcome': 'REJECTED', 'order_id': None,
             'rejection_reason': 'SALE_SOLD_OUT'},
            *abuse_results]
        order = lambda order_id, member, key, status: {
            'id': order_id, 'member_id': member, 'key': key, 'sale_id': 1, 'quantity': 1, 'unit_price': 1000,
            'total_amount': 1000, 'status': status, 'public_id': f'p{order_id}'}
        orders = [order(1, 100, 'key-n0', 'CONFIRMED'), order(2, 200, 'abuse-1', orders_status)]
        abuse = abuse_attempts(abuser_ids, results)
        manifest = build_manifest(normal, abuse, 1, plan)
        data = {'final': True, 'manifest': manifest, 'requests': verifier_requests(normal, abuse, 1, results),
                'sales': [{'id': 1, 'unit_price': 1000, 'total_quantity': 2, 'reserved_quantity': 0,
                           'sold_quantity': 2, 'per_member_limit': 1}],
                'buyers': [{'sale_id': 1, 'member_id': 100, 'reserved_quantity': 0, 'purchased_quantity': 1},
                           {'sale_id': 1, 'member_id': 200, 'reserved_quantity': 0, 'purchased_quantity': 1}],
                'orders': orders,
                'reservations': [{'order_id': o['id'], 'sale_id': 1, 'member_id': o['member_id'], 'quantity': 1,
                                  'status': 'CONFIRMED'} for o in orders],
                'payments': [{'order_id': o['id'], 'pg_key': f'pg{o["id"]}', 'amount': 1000, 'status': 'SUCCEEDED'}
                             for o in orders],
                'pg_payments': [{'pg_key': f'pg{o["id"]}', 'amount': 1000, 'status': 'SUCCEEDED'} for o in orders],
                'request_results': results, 'recovery': None}
        return data, abuse, results, orders

    ABUSE_RESULTS = [
        {'member_id': 200, 'key': 'abuse-1', 'sale_id': 1, 'quantity': 1, 'outcome': 'CREATED', 'order_id': 2,
         'rejection_reason': None},
        {'member_id': 200, 'key': 'abuse-2', 'sale_id': 1, 'quantity': 1, 'outcome': 'REJECTED', 'order_id': None,
         'rejection_reason': 'PURCHASE_LIMIT_EXCEEDED'},
        {'member_id': 201, 'key': 'abuse-3', 'sale_id': 1, 'quantity': 1, 'outcome': 'REJECTED', 'order_id': None,
         'rejection_reason': 'SALE_SOLD_OUT'}]

    def test_attempts_come_only_from_the_abusive_members_stored_results(self):
        _, abuse, results, orders = self.snapshot(self.ABUSE_RESULTS)
        self.assertEqual([a['key'] for a in abuse], ['abuse-1', 'abuse-2', 'abuse-3'])
        self.assertEqual(abuse_server_summary({200, 201}, results, orders),
                         {'stored_requests': 3, 'created': 1,
                          'rejections': {'PURCHASE_LIMIT_EXCEEDED': 1, 'SALE_SOLD_OUT': 1}, 'confirmed': 1})

    def test_the_verifier_accepts_a_consistent_run_with_abusive_users(self):
        data, *_ = self.snapshot(self.ABUSE_RESULTS)
        self.assertEqual(verify(data), [])

    def test_many_requests_from_a_member_sharing_one_session_are_accepted(self):
        # Five VUs of one member race: one creates, the rest are rejected by the limit of one per member.
        crowd = [{'member_id': 201, 'key': f'abuse-x{i}', 'sale_id': 1, 'quantity': 1, 'outcome': 'REJECTED',
                  'order_id': None, 'rejection_reason': 'PURCHASE_LIMIT_EXCEEDED'} for i in range(500)]
        data, abuse, *_ = self.snapshot([*self.ABUSE_RESULTS, *crowd])
        self.assertEqual(len(abuse), 503)
        self.assertEqual(verify(data), [])

    def test_an_unconfirmed_abusive_order_is_a_violation(self):
        data, *_ = self.snapshot(self.ABUSE_RESULTS, orders_status='AWAITING_PAYMENT')
        self.assertNotEqual(verify(data), [])

    def test_an_abusive_rejection_for_another_reason_is_a_violation(self):
        odd = [*self.ABUSE_RESULTS[:2], {**self.ABUSE_RESULTS[2], 'rejection_reason': 'SALE_STOPPED'}]
        data, *_ = self.snapshot(odd)
        self.assertIn('attempt abuse-201-abuse-3: rejection reason', verify(data))

    def test_normal_users_keep_one_planned_request_each(self):
        data, *_ = self.snapshot(self.ABUSE_RESULTS)
        data['request_results'] = [r for r in data['request_results'] if r['key'] != 'key-n3']
        data['requests'] = build_requests(
            [{'id': a['attempt_id'], 'member_id': a['member_id'], 'key': a['key']} for a in data['manifest']['attempts']],
            1, data['request_results'])
        self.assertTrue(any('not sent' in error for error in verify(data)))


class AggregateTest(unittest.TestCase):
    def summary(self, throughput, confirmed):
        return {'k6': {'http_requests_per_second': throughput, 'duration_seconds': 10.0,
                       'normal_duration_seconds': 9.0, 'server_errors_5xx': 0, 'connection_errors': 0,
                       'endpoints': {'order_create': endpoint(10, {'201': 5}, {'ORDER_CREATED': 5}),
                                     'sale_detail': endpoint()}},
                'db': {'confirmed': confirmed, 'remaining': 0, 'orders': confirmed, 'confirmed_normal': confirmed,
                       'confirmed_abuse': 0},
                'derived': {'cache_hit_rate': None, 'actual_order_throughput_per_second': confirmed / 10},
                'cache': {'hit': 0, 'miss': 0, 'error': 0}, 'abuse': {'stored_requests': 0},
                'rate_limit': NO_LIMIT,
                'management': {'combined': {'hikari': {'active_peak': 10, 'pending_peak': 191, 'acquire_mean_ms': 1.0,
                                                       'acquire_max_ms_peak': 2.0},
                                            'jvm': {'cpu_seconds': 40.0, 'system_cpu_usage_mean': 0.5,
                                                    'process_cpu_usage_peak': 0.1, 'system_cpu_usage_peak': 0.9,
                                                    'gc_pause_seconds': 0.0, 'tomcat_threads_busy_peak': None}}},
                'mysql': {'row_lock_waits': 5, 'row_lock_time_ms': 100, 'deadlocks': 0, 'lock_timeouts': 0},
                'load_generator': {'cpu_seconds': 4.0}, 'durations_seconds': {'total_seconds': 100.0}}

    def test_median_min_max_and_values_per_number(self):
        metrics = aggregate([self.summary(100.0, 1000), self.summary(300.0, 1000), self.summary(200.0, 1000)])
        row = metrics['throughput.http_requests_per_second']
        self.assertEqual((row['median'], row['min'], row['max'], row['values']), (200.0, 100.0, 300.0,
                                                                                    [100.0, 300.0, 200.0]))
        self.assertEqual(metrics['database.confirmed']['median'], 1000)
        self.assertEqual(metrics['endpoints.order_create.p95_ms']['median'], 2)

    def test_headline_leaves_out_endpoints_that_saw_no_request_and_numbers_that_are_missing(self):
        flat = headline(self.summary(100.0, 1000))
        self.assertNotIn('endpoints.sale_detail.p50_ms', flat)
        self.assertNotIn('derived.cache_hit_rate', flat)
        self.assertNotIn('management.tomcat_threads_busy_peak', flat)
        self.assertIn('rate_limit.create.rejected', flat)


if __name__ == '__main__':
    unittest.main()
