import json
import unittest
import uuid

from balanced import (evaluate, expected_counts, parse_access_log, plan_attempts, split,
                      summarize_access_log)


def log_line(method, uri, upstream, status=200):
    entry = {'level': 'info', 'logger': 'http.log.access.log0', 'msg': 'handled request', 'status': status,
             'request': {'method': method, 'uri': uri, 'host': '127.0.0.1:8000'}}
    if upstream is not None:
        entry['upstream'] = upstream
    return json.dumps(entry)


A, B = 'host.docker.internal:8080', 'host.docker.internal:8082'


def passing_result(**changes):
    result = {'stock': 300, 'confirmed': 300, 'remaining': 0, 'verifier_violations': 0, 'evidence_violations': 0,
              'sql_violations': 0, 'request_errors': 0, 'attempts': 10, 'upstreams': [A, B],
              'access': {'order_creates': {A: 6, B: 4}, 'order_payments': {A: 1, B: 1}, 'auth_failures': 0}}
    result.update(changes)
    return result


class AccessLogTest(unittest.TestCase):
    def test_counts_only_order_creates_per_upstream(self):
        text = '\n'.join([
            log_line('POST', '/api/v1/orders', A, 201),
            log_line('POST', '/api/v1/orders', B, 409),
            log_line('POST', '/api/v1/orders', B, 201),
            log_line('POST', '/api/v1/orders?x=1', A, 201),
            log_line('GET', '/api/v1/orders', A),
            log_line('POST', '/api/v1/orders/abc/payment', A),
            log_line('POST', '/api/v1/sessions', B),
            'not json',
            '{"level":"info","msg":"no request field"}',
            ''])
        summary = summarize_access_log(parse_access_log(text))
        self.assertEqual({A: 2, B: 2}, summary['order_creates'])
        self.assertEqual({A: 1}, summary['order_payments'])
        self.assertEqual({A: 4, B: 3}, summary['all_requests'])

    def test_unrouted_requests_and_auth_failures_are_visible(self):
        text = '\n'.join([log_line('POST', '/api/v1/orders', None, 502), log_line('POST', '/api/v1/orders', A, 403),
                          log_line('GET', '/api/v1/members/me', B, 401)])
        summary = summarize_access_log(parse_access_log(text))
        self.assertEqual({'(none)': 1, A: 1}, summary['order_creates'])
        self.assertEqual(2, summary['auth_failures'])

    def test_split_reports_share(self):
        self.assertEqual({A: {'requests': 3, 'share': .75}, B: {'requests': 1, 'share': .25}}, split({B: 1, A: 3}))
        self.assertEqual({}, split({}))


class EvaluateTest(unittest.TestCase):
    def test_passing_two_instance_run(self):
        self.assertEqual([], evaluate(passing_result()))

    def test_one_instance_run_passes_with_one_upstream(self):
        self.assertEqual([], evaluate(passing_result(upstreams=[A], access={
            'order_creates': {A: 10}, 'order_payments': {A: 2}, 'auth_failures': 0})))

    def test_each_failed_criterion_is_reported(self):
        failing = {
            'confirmed': passing_result(confirmed=299),
            'remaining': passing_result(remaining=1),
            'verifier': passing_result(verifier_violations=1),
            'evidence': passing_result(evidence_violations=2),
            'sql': passing_result(sql_violations=1),
            'errors': passing_result(request_errors=1),
            'auth': passing_result(access={'order_creates': {A: 6, B: 4}, 'order_payments': {}, 'auth_failures': 1}),
            'second instance idle': passing_result(access={'order_creates': {A: 10}, 'order_payments': {},
                                                           'auth_failures': 0}),
            'unknown upstream': passing_result(access={'order_creates': {A: 5, B: 4, 'other:1': 1},
                                                       'order_payments': {}, 'auth_failures': 0}),
            'log shorter than attempts': passing_result(access={'order_creates': {A: 5, B: 4}, 'order_payments': {},
                                                                'auth_failures': 0}),
            'single instance used twice': passing_result(upstreams=[A], access={
                'order_creates': {A: 5, B: 5}, 'order_payments': {}, 'auth_failures': 0}),
        }
        for label, result in failing.items():
            with self.subTest(label=label):
                self.assertTrue(evaluate(result))


class PlanTest(unittest.TestCase):
    def plan(self, users=20, every=10):
        return plan_attempts(list(range(100, 100 + users)), 7, lambda: str(uuid.uuid4()),
                             lambda: str(uuid.uuid4()), every)

    def test_every_user_has_one_primary_and_duplicates_target_the_same_user(self):
        plans = self.plan()
        self.assertEqual(20, sum(p['group'] == 'primary' for p in plans))
        self.assertEqual(2 * 4, sum(p['group'] != 'primary' for p in plans))
        self.assertEqual(len(plans), len({p['id'] for p in plans}))
        primary = {p['client']: p['key'] for p in plans if p['group'] == 'primary'}
        for plan in plans:
            self.assertEqual(100 + plan['client'], plan['member_id'])
            if plan['group'] == 'same_key_replay':
                self.assertEqual(primary[plan['client']], plan['key'])
            if plan['group'] == 'different_key':
                self.assertNotEqual(primary[plan['client']], plan['key'])
                self.assertEqual(0, plan['client'] % 10)
        self.assertEqual('SALE_SOLD_OUT', next(p['reason'] for p in plans if p['client'] == 1))
        self.assertIsInstance(next(p['reason'] for p in plans if p['client'] == 0), list)

    def test_seeded_shuffle_keeps_the_attempts_and_varies_the_order(self):
        def build(seed):
            counter = iter(range(10_000))
            return plan_attempts(list(range(40)), 7, lambda: f'a{next(counter)}', lambda: f'k{next(counter)}',
                                 shuffle_seed=seed)
        plain, shuffled, again = build(None), build(3), build(3)
        self.assertEqual(sorted(p['id'] for p in plain), sorted(p['id'] for p in shuffled))
        self.assertNotEqual([p['id'] for p in plain], [p['id'] for p in shuffled])
        self.assertEqual([p['id'] for p in shuffled], [p['id'] for p in again])

    def test_expected_counts_cover_either_key_winning(self):
        plans = self.plan(users=1000)
        results, statuses = expected_counts(plans, 300)
        self.assertEqual(1400, len(plans))
        self.assertEqual({'CREATED': [300, 500], 'REJECTED': [900, 1100]}, results)
        self.assertEqual({'CONFIRMED': 300}, statuses)


if __name__ == '__main__':
    unittest.main()
