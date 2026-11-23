import asyncio
import json
import os
from types import SimpleNamespace
import unittest

os.environ.setdefault('JAVA_HOME', '/unused')

from redis_outage import (Backoff, Decision, Outcome, cache_mismatches, classify, counter_deltas, decide,
                          dirty_outside_results, distribution, evaluate, failures, first_success_after,
                          instant_micros, labelled_delta, lb_attempt_index, lb_error_entries, non_policy_failures,
                          parse_retry_after, parse_timeout_seconds, percentile, phase_of, probe_summary,
                          server_record_check, summarize_attempts, throughput, timeout_label, trailing_up,
                          user_summary)
from run_redis_outage import Clock, Deadline, Experiment

OUTAGE = {'stop_issued_s': 10.0, 'stop_returned_s': 10.5, 'start_issued_s': 25.5, 'start_returned_s': 26.0}


def attempt(step='create', status=201, code='ORDER_CREATED', finished=1.0, latency=10.0, user=0, error=None, **more):
    return {'seq': 1, 'user': user, 'step': step, 'method': 'POST', 'status': status, 'code': code,
            'finished_s': finished, 'started_s': finished - latency / 1000, 'latency_ms': latency, 'error': error,
            **more}


def unavailable(finished, latency=1000.0, user=0, step='create'):
    return attempt(step, 503, 'AUTHENTICATION_UNAVAILABLE', finished, latency, user)


class TimeoutTest(unittest.TestCase):
    def test_timeout_needs_an_explicit_unit_because_a_bare_number_means_milliseconds(self):
        self.assertEqual(1.0, parse_timeout_seconds('1s'))
        self.assertEqual(0.5, parse_timeout_seconds('500ms'))
        for bad in ('2', '0s', 'PT1S', '', None, '-1s', '1.5s'):
            with self.assertRaises(ValueError):
                parse_timeout_seconds(bad)
        self.assertEqual('timeout-2s', timeout_label('2s'))


class DecisionTest(unittest.TestCase):
    def test_business_rejection_is_final_and_sold_out_is_named(self):
        self.assertEqual(Decision('finish', result='SOLD_OUT'), decide('create', Outcome(409, 'SALE_SOLD_OUT')))
        self.assertEqual('REJECTED:PURCHASE_LIMIT_EXCEEDED',
                         decide('create', Outcome(409, 'PURCHASE_LIMIT_EXCEEDED')).result)

    def test_created_and_replayed_orders_proceed_to_payment(self):
        self.assertEqual('proceed', decide('create', Outcome(201, 'ORDER_CREATED')).kind)
        self.assertEqual('proceed', decide('create', Outcome(200, 'ORDER_FOUND')).kind)

    def test_unavailable_waits_a_backoff_and_repeats_the_request(self):
        for step in ('create', 'payment', 'csrf', 'login'):
            self.assertEqual(Decision('retry', None, reason='unavailable'),
                             decide(step, Outcome(503, 'AUTHENTICATION_UNAVAILABLE')))

    def test_rate_limit_waits_exactly_retry_after_and_defaults_to_one_second(self):
        self.assertEqual(Decision('retry', 3.0, reason='rate_limited'),
                         decide('create', Outcome(429, 'ORDER_RATE_LIMITED', 3.0)))
        self.assertEqual(1.0, decide('payment', Outcome(429, 'ORDER_RATE_LIMITED')).wait)

    def test_lost_session_logs_in_again_for_orders_and_payments_only(self):
        for status, code in ((401, 'AUTHENTICATION_REQUIRED'), (403, 'CSRF_INVALID')):
            for step in ('create', 'payment'):
                self.assertEqual('relogin', decide(step, Outcome(status, code)).kind)
        self.assertEqual('fail', decide('login', Outcome(401, 'INVALID_CREDENTIALS')).kind)
        self.assertEqual('fail', decide('create', Outcome(403, 'ACCESS_DENIED')).kind)

    def test_other_server_errors_and_missing_answers_are_retried_with_the_same_key_but_counted_elsewhere(self):
        self.assertEqual('server_error', decide('create', Outcome(500, 'X')).reason)
        self.assertEqual('connection_error', decide('create', Outcome(error='TimeoutError')).reason)

    def test_payment_outcomes(self):
        self.assertEqual(Decision('finish', result='CONFIRMED'), decide('payment', Outcome(200, 'PAYMENT_SUCCEEDED')))
        self.assertEqual(Decision('retry', 1.0, reason='payment_pending'),
                         decide('payment', Outcome(202, 'PAYMENT_PENDING')))
        self.assertEqual('PAYMENT_DECLINED', decide('payment', Outcome(409, 'PAYMENT_DECLINED')).result)
        self.assertEqual('fail', decide('payment', Outcome(400, 'INVALID')).kind)

    def test_unexpected_client_error_ends_the_user_instead_of_looping(self):
        self.assertEqual('fail', decide('create', Outcome(400, 'INVALID_REQUEST')).kind)

    def test_classification_separates_policy_503_from_other_5xx(self):
        self.assertEqual('auth_unavailable', classify(Outcome(503, 'AUTHENTICATION_UNAVAILABLE')))
        self.assertEqual('other_5xx', classify(Outcome(503, 'SOMETHING_ELSE')))
        self.assertEqual('other_5xx', classify(Outcome(500, None)))
        self.assertEqual('other_5xx', classify(Outcome(502, None)))
        self.assertEqual('connection_error', classify(Outcome(error='ConnectionResetError')))
        self.assertEqual('rate_limited', classify(Outcome(429, 'ORDER_RATE_LIMITED')))
        self.assertEqual('session_rejected', classify(Outcome(401, 'AUTHENTICATION_REQUIRED')))
        self.assertEqual('business', classify(Outcome(409, 'SALE_SOLD_OUT')))

    def test_retry_after_parsing(self):
        self.assertEqual(2.0, parse_retry_after('2'))
        for bad in (None, '', 'soon', '-1'):
            self.assertIsNone(parse_retry_after(bad))


class BackoffTest(unittest.TestCase):
    def test_delays_grow_to_the_cap_stay_inside_the_jitter_and_replay_with_the_same_seed(self):
        first = [Backoff('s').delay(n) for n in range(1, 8)]
        again = [Backoff('s').delay(n) for n in range(1, 8)]
        self.assertEqual(first, again)
        backoff = Backoff('s')
        for streak, nominal in ((1, .25), (2, .5), (3, 1.0), (9, 1.0)):
            self.assertTrue(nominal * .8 <= backoff.delay(streak) <= nominal * 1.2)


class PhaseAndStatisticsTest(unittest.TestCase):
    def test_phase_boundaries_use_the_completion_time(self):
        self.assertEqual('before', phase_of(9.999, OUTAGE))
        self.assertEqual('during', phase_of(10.0, OUTAGE))
        self.assertEqual('during', phase_of(25.999, OUTAGE))
        self.assertEqual('after', phase_of(26.0, OUTAGE))

    def test_percentiles_are_nearest_rank(self):
        values = list(range(1, 101))
        self.assertEqual(50, percentile(values, .5))
        self.assertEqual(95, percentile(values, .95))
        self.assertEqual(99, percentile(values, .99))
        self.assertEqual({'count': 0, 'p50_ms': None, 'p95_ms': None, 'p99_ms': None, 'max_ms': None},
                         distribution([]))
        self.assertEqual(100.0, distribution(values)['max_ms'])

    def test_summary_counts_by_phase_and_measures_only_unavailable_latency(self):
        attempts = [attempt(finished=1.0), attempt('payment', 200, 'PAYMENT_SUCCEEDED', 2.0),
                    unavailable(12.0, 1000.0), unavailable(13.0, 1100.0), unavailable(14.0, 2000.0),
                    attempt('create', 429, 'ORDER_RATE_LIMITED', 27.0), attempt(finished=28.0),
                    attempt('probe', 200, None, 12.0)]
        summary = summarize_attempts(attempts, OUTAGE)
        self.assertEqual({'201 ORDER_CREATED': 1, '200 PAYMENT_SUCCEEDED': 1}, summary['by_phase']['before']['status_code'])
        self.assertEqual({'503 AUTHENTICATION_UNAVAILABLE': 3}, summary['by_phase']['during']['status_code'])
        self.assertEqual(2, summary['by_phase']['after']['attempts'])
        self.assertEqual(3, summary['auth_unavailable_latency']['all']['count'])
        self.assertEqual(2000.0, summary['auth_unavailable_latency']['during']['max_ms'])
        self.assertEqual(0, summary['auth_unavailable_latency']['before']['count'])
        self.assertEqual(3, summary['by_step']['create']['during']['503 AUTHENTICATION_UNAVAILABLE'])

    def test_only_unavailable_503_is_allowed_and_probes_are_judged_separately(self):
        attempts = [unavailable(12.0), attempt(status=500, code=None, finished=12.0),
                    attempt(status=503, code='OTHER', finished=12.0), attempt(status=502, code=None, finished=12.0),
                    attempt(status=None, code=None, error='TimeoutError', finished=12.0),
                    attempt('probe', 500, None, 12.0)]
        self.assertEqual({'other_5xx': 3, 'connection_errors': 1}, non_policy_failures(attempts))
        self.assertEqual({'other_5xx': 0, 'connection_errors': 0}, non_policy_failures([unavailable(12.0)]))
        split = non_policy_failures(attempts + [attempt(status=500, code=None, finished=30.0)], OUTAGE)
        self.assertEqual({'before': {'other_5xx': 0, 'connection_errors': 0},
                          'during': {'other_5xx': 3, 'connection_errors': 1},
                          'after': {'other_5xx': 1, 'connection_errors': 0}}, split['by_phase'])

    def test_throughput_per_phase_counts_business_responses_and_finished_users(self):
        attempts = [attempt(finished=t) for t in (1, 2, 3, 4)] + [unavailable(t) for t in (11, 12)] + [
            attempt(finished=t) for t in (27, 28, 29, 30)]
        flows = [{'finished_s': 4.0}, {'finished_s': 29.0}, {'finished_s': 30.0}]
        result = throughput(attempts, flows, OUTAGE, 30.0)
        self.assertEqual({'seconds': 10.0, 'responses': 4, 'responses_per_second': .4, 'business_responses': 4,
                          'business_responses_per_second': .4, 'users_finished': 1, 'users_finished_per_second': .1},
                         result['before'])
        self.assertEqual(0, result['during']['business_responses'])
        self.assertEqual(2, result['during']['responses'])
        self.assertEqual(2, result['after']['users_finished'])
        self.assertEqual(4.0, result['after']['seconds'])

    def test_probe_summary_flags_non_200_only_inside_the_outage(self):
        probes = [attempt('probe', 200, 'SALE_FOUND', 5.0), attempt('probe', 200, 'SALE_FOUND', 12.0, 1500.0),
                  attempt('probe', 500, None, 13.0), attempt('probe', 500, None, 30.0)]
        result = probe_summary(probes, OUTAGE)
        self.assertEqual(1, result['non_200_during_outage'])
        self.assertEqual(2, result['by_phase']['during']['count'])
        self.assertEqual(1500.0, result['by_phase']['during']['latency']['p95_ms'] or 1500.0)

    def test_recovery_is_measured_from_the_end_of_the_outage_to_the_first_business_response(self):
        attempts = [unavailable(26.4), unavailable(26.8), attempt(finished=27.3),
                    attempt('login', 200, 'LOGIN_SUCCEEDED', 26.5), attempt(finished=5.0)]
        self.assertEqual(1.3, first_success_after(attempts, OUTAGE))
        self.assertIsNone(first_success_after([unavailable(30)], OUTAGE))

    def test_health_recovery_uses_the_start_of_the_trailing_up_run(self):
        samples = [{'instance': 'a', 't': t, 'status': s} for t, s in
                   ((1, 'UP'), (11, 'DOWN'), (27, 'DOWN'), (28.5, 'UP'), (29, 'UP'))]
        samples += [{'instance': 'b', 't': 28, 'status': 'UP'}, {'instance': 'b', 't': 29, 'status': 'DOWN'}]
        result = trailing_up(samples, 26.0)
        self.assertEqual({'last_sample_up': True, 'down_observed': True, 'samples': 5, 'up_since_s': 28.5,
                          'seconds_after_outage': 2.5}, result['a'])
        self.assertFalse(result['b']['last_sample_up'])
        self.assertIsNone(result['b']['up_since_s'])

    def test_user_summary(self):
        flows = [{'result': 'CONFIRMED', 'retries': {'unavailable': 2}, 'relogins': 0, 'requests': 4},
                 {'result': 'SOLD_OUT', 'retries': {}, 'relogins': 1, 'requests': 2}]
        self.assertEqual({'results': {'CONFIRMED': 1, 'SOLD_OUT': 1}, 'relogins': 1, 'users_relogged_in': 1,
                          'retries_total': 2, 'retries_by_reason': {'unavailable': 2}, 'users_retried': 1,
                          'max_requests_per_user': 4}, user_summary(flows))


def cache_json(**changes):
    value = {'saleId': 7, 'productId': 3, 'name': 'n', 'description': '', 'productNameSnapshot': 'p',
             'unitPrice': 1000, 'startsAt': '2026-11-23T01:00:03Z', 'endsAt': '2026-11-23T02:00:03.500Z',
             'totalQuantity': 300, 'perMemberLimit': 1, 'soldQuantity': 300, 'stopped': False, 'version': 9,
             'createdAt': '2026-11-23T01:00:00.123456Z', 'updatedAt': '2026-11-23T01:00:09.000001Z'}
    value.update(changes)
    return value


def row_json(**changes):
    value = cache_json(startsAt='2026-11-23T01:00:03.000000Z', endsAt='2026-11-23T02:00:03.500000Z',
                       createdAt='2026-11-23T01:00:00.123456Z', updatedAt='2026-11-23T01:00:09.000001Z', stopped=0)
    value.update(changes)
    return value


class CacheComparisonTest(unittest.TestCase):
    def test_times_compare_by_instant_whatever_the_fraction_length(self):
        self.assertEqual(instant_micros('2026-11-23T01:00:03Z'), instant_micros('2026-11-23T01:00:03.000000Z'))
        self.assertEqual(instant_micros('2026-11-23T01:00:03.5Z') + 0, instant_micros('2026-11-23T01:00:03.500000Z'))
        self.assertEqual(instant_micros('2026-11-23T01:00:03Z'), instant_micros(1795395603))

    def test_equal_values_match_with_a_boolean_stored_as_a_number(self):
        self.assertEqual([], cache_mismatches(cache_json(), row_json()))

    def test_each_field_difference_is_named(self):
        self.assertEqual(['soldQuantity', 'version'],
                         cache_mismatches(cache_json(soldQuantity=250, version=5), row_json()))
        self.assertEqual(['stopped'], cache_mismatches(cache_json(stopped=True), row_json()))
        self.assertEqual(['updatedAt'], cache_mismatches(cache_json(updatedAt='2026-11-23T01:00:09.000002Z'), row_json()))

    def test_missing_and_unexpected_fields_are_mismatches(self):
        cached = cache_json()
        del cached['name']
        self.assertEqual(['name'], cache_mismatches(cached, row_json()))
        self.assertEqual(['unexpected:extra'], cache_mismatches(cache_json(extra=1), row_json()))


def identity(member, key):
    return {'member_id': member, 'key': key}


class ServerRecordTest(unittest.TestCase):
    users = {0: identity(10, 'k0'), 1: identity(11, 'k1'), 2: identity(12, 'k2')}

    def run_check(self, attempts, results, orders):
        return server_record_check(self.users, attempts, results, orders)

    def test_matching_records_pass_even_when_the_first_tries_were_unavailable(self):
        attempts = [unavailable(12, user=0), attempt(user=0), attempt(status=409, code='SALE_SOLD_OUT', user=1),
                    attempt(status=200, code='ORDER_FOUND', user=2)]
        results = [{'member_id': 10, 'key': 'k0', 'outcome': 'CREATED'},
                   {'member_id': 11, 'key': 'k1', 'outcome': 'REJECTED'},
                   {'member_id': 12, 'key': 'k2', 'outcome': 'CREATED'}]
        orders = [{'member_id': 10, 'key': 'k0'}, {'member_id': 12, 'key': 'k2'}]
        result = self.run_check(attempts, results, orders)
        self.assertEqual([], result['errors'])
        self.assertEqual((2, 1, 0, 0), (result['created_users'], result['rejected_users'],
                                        result['unanswered_users'], result['duplicate_orders_per_key']))

    def test_a_key_that_only_saw_503_or_429_must_have_no_stored_result_or_order(self):
        attempts = [unavailable(12, user=0), attempt(status=429, code='ORDER_RATE_LIMITED', user=0)]
        clean = self.run_check(attempts, [], [])
        self.assertEqual([], clean['errors'])
        self.assertEqual(3, clean['unanswered_users'])
        leaked = self.run_check(attempts, [{'member_id': 10, 'key': 'k0', 'outcome': 'CREATED'}],
                                [{'member_id': 10, 'key': 'k0'}])
        self.assertEqual(1, len(leaked['errors']))

    def test_disagreements_and_duplicates_are_reported(self):
        attempts = [attempt(user=0), attempt(status=409, code='SALE_SOLD_OUT', user=1)]
        results = [{'member_id': 10, 'key': 'k0', 'outcome': 'CREATED'},
                   {'member_id': 11, 'key': 'k1', 'outcome': 'CREATED'},
                   {'member_id': 99, 'key': 'zz', 'outcome': 'REJECTED'}]
        orders = [{'member_id': 10, 'key': 'k0'}, {'member_id': 10, 'key': 'k0'}, {'member_id': 11, 'key': 'k1'}]
        result = self.run_check(attempts, results, orders)
        self.assertEqual(1, result['duplicate_orders_per_key'])
        self.assertEqual(3, len(result['errors']))


class AccessLogTest(unittest.TestCase):
    def test_attempts_are_joined_by_header_and_nothing_else_is_kept(self):
        line = json.dumps({'status': 503, 'upstream': 'host:1', 'request': {
            'headers': {'X-Attempt-Seq': ['12'], 'X-Csrf-Token': ['secret']}}})
        other = json.dumps({'status': 200, 'upstream': 'host:2', 'request': {'headers': {}}})
        self.assertEqual({12: {'upstream': 'host:1', 'status': 503}}, lb_attempt_index('\n'.join([line, other, 'x'])))


class CaddyErrorTest(unittest.TestCase):
    def test_error_lines_keep_the_message_and_attempt_but_not_the_headers(self):
        error = json.dumps({'level': 'error', 'ts': 1.5, 'logger': 'http.log.error', 'msg': 'dial tcp: i/o timeout',
                            'status': 502, 'request': {'headers': {'X-Attempt-Seq': ['7'], 'X-Csrf-Token': ['s']}}})
        info = json.dumps({'level': 'info', 'msg': 'started'})
        entries = lb_error_entries('\n'.join([info, error, 'not json']))
        self.assertEqual([{'ts': 1.5, 'level': 'error', 'logger': 'http.log.error', 'msg': 'dial tcp: i/o timeout',
                           'status': 502, 'seq': 7}], entries)


class MetricsTest(unittest.TestCase):
    def test_counter_deltas_keep_only_changed_counters_with_the_prefix(self):
        before = {('sale_cache_requests_total', (('result', 'error'),)): 1.0,
                  ('sale_cache_requests_total', (('result', 'hit'),)): 5.0,
                  ('jvm_gc_pause_seconds_count', ()): 2.0, ('sale_cache_gauge', ()): 1.0}
        after = {('sale_cache_requests_total', (('result', 'error'),)): 31.0,
                 ('sale_cache_requests_total', (('result', 'hit'),)): 5.0,
                 ('jvm_gc_pause_seconds_count', ()): 9.0, ('sale_cache_gauge', ()): 7.0}
        rows = counter_deltas(before, after, ('sale_cache',))
        self.assertEqual([{'name': 'sale_cache_requests_total', 'labels': {'result': 'error'}, 'delta': 30.0}], rows)
        self.assertEqual(30.0, labelled_delta(rows, 'sale_cache_requests_total', result='error'))
        self.assertEqual(0, labelled_delta(rows, 'sale_cache_requests_total', result='hit'))

    def test_result_files_do_not_make_the_tree_dirty_but_other_changes_do(self):
        results = '?? benchmarks/redis-outage/timeout-1s/run-1.json\n'
        self.assertFalse(dirty_outside_results(results))
        self.assertTrue(dirty_outside_results(results + ' M tools/order-regression/isolated.py\n'))
        self.assertTrue(dirty_outside_results('?? scratch.txt\n'))


def passing():
    probes = {'by_phase': {'before': {'count': 3, 'errors': 0}, 'during': {'count': 30, 'errors': 0},
                           'after': {'count': 5, 'errors': 0}}, 'non_200_during_outage': 0}
    return {'users': 1000, 'stock': 300,
            'db': {'confirmed': 300, 'remaining': 0, 'converged': True}, 'verifier_violations': 0, 'sql_violations': 0,
            'server_records': {'duplicate_orders_per_key': 0, 'errors': []},
            'flows': {'CONFIRMED': 300, 'SOLD_OUT': 700}, 'attempt_failures': {'other_5xx': 0, 'connection_errors': 0},
            'unavailable_seen_by_clients': 120, 'probes': probes,
            'cache': {'error_delta': 30, 'regenerated_after_recovery': True, 'final_mismatched_fields': []},
            'client_429': 0, 'server_rate_limited': 0,
            'health': {'sales1': {'last_sample_up': True}, 'sales2': {'last_sample_up': True}}, 'clock_errors': []}


class EvaluationTest(unittest.TestCase):
    def test_a_clean_run_passes_every_criterion(self):
        self.assertEqual([], failures(evaluate(passing())))

    def failing(self, **changes):
        result = passing()
        for path, value in changes.items():
            target = result
            *parents, last = path.split('__')
            for name in parents:
                target = target[name]
            target[last] = value
        return failures(evaluate(result))

    def test_each_policy_violation_fails_its_own_criterion(self):
        cases = {
            'db__confirmed': (299, 'confirmed'),
            'db__remaining': (1, 'remaining'),
            'db__converged': (False, 'orders_converged_to_terminal'),
            'verifier_violations': (1, 'verifier_violations'),
            'sql_violations': (2, 'reconciliation_violations'),
            'server_records__duplicate_orders_per_key': (1, 'duplicate_orders_per_request_key'),
            'server_records__errors': (['x'], 'rejected_requests_left_no_record_or_order'),
            'flows': ({'CONFIRMED': 299, 'SOLD_OUT': 700, 'DEADLINE_EXCEEDED': 1},
                      'completed_within_deadline'),
            'attempt_failures__other_5xx': (1, 'server_errors_other_than_503_authentication_unavailable'),
            'attempt_failures__connection_errors': (1, 'connection_errors'),
            'unavailable_seen_by_clients': (0, 'outage_was_seen_by_clients'),
            'probes__non_200_during_outage': (1, 'public_detail_probes_during_outage_all_200'),
            'cache__error_delta': (0, 'sale_cache_error_counter_increased'),
            'cache__regenerated_after_recovery': (False, 'cache_regenerated_after_recovery'),
            'cache__final_mismatched_fields': (['soldQuantity'], 'cache_matches_database'),
            'server_rate_limited': (3, 'rate_limit_rejections_match_clients'),
            'clock_errors': (['drift'], 'clock_guard'),
        }
        for path, (value, expected) in cases.items():
            with self.subTest(path):
                self.assertIn(expected, self.failing(**{path: value}))

    def test_no_probe_during_the_outage_and_unhealthy_instance_fail(self):
        result = passing()
        result['probes']['by_phase']['during']['count'] = 0
        result['health']['sales2']['last_sample_up'] = False
        self.assertEqual(['public_detail_probes_during_outage_not_empty', 'management_health_up_after_recovery'],
                         failures(evaluate(result)))

    def test_a_business_rejection_other_than_sold_out_fails_the_user_criteria(self):
        failed = self.failing(flows={'CONFIRMED': 299, 'SOLD_OUT': 700, 'REJECTED:PURCHASE_LIMIT_EXCEEDED': 1})
        self.assertEqual(['users_confirmed', 'users_other_results'], failed)


class FakeClient:
    """Answers scripted (status, body, headers) in call order and records what was sent."""

    def __init__(self, script):
        self.script = list(script)
        self.calls = []
        self.cookies = {'SESSION': 'secret-session'}
        self.csrf = 'secret-csrf'

    async def request_full(self, method, path, body=None, headers=None, timeout=35):
        self.calls.append({'method': method, 'path': path, 'headers': dict(headers or {}), 'cookies': dict(self.cookies)})
        status, response, response_headers = self.script.pop(0)
        if path == '/api/v1/sessions' and status == 200:
            self.cookies = {'SESSION': 'new-session'}
        return status, response, response_headers


def reply(status, code=None, data=None, **headers):
    return status, {'code': code, 'data': data}, headers


def csrf_reply():
    return reply(200, 'CSRF_TOKEN_ISSUED', {'token': 'new-csrf'})


class FlowTest(unittest.IsolatedAsyncioTestCase):
    def experiment(self):
        experiment = Experiment(SimpleNamespace(password='pw-secret', ports={}), SimpleNamespace(seed='t'), Clock())
        experiment.sale_id = 7
        experiment.waits = []

        async def pause(seconds):
            experiment.waits.append(round(seconds, 2))
        experiment.pause = pause
        return experiment

    async def flow(self, experiment, script, **options):
        client = FakeClient(script)
        result = {'user': 0, 'result': None, 'retries': __import__('collections').Counter(), 'relogins': 0,
                  'requests': 0}
        await experiment.run_user(0, client, 'the-key', result)
        return client, result

    async def test_unavailable_is_retried_with_the_same_key_until_the_order_is_paid(self):
        experiment = self.experiment()
        script = [reply(503, 'AUTHENTICATION_UNAVAILABLE'), reply(503, 'AUTHENTICATION_UNAVAILABLE'),
                  reply(201, 'ORDER_CREATED', {'orderId': 'o-1'}), reply(503, 'AUTHENTICATION_UNAVAILABLE'),
                  reply(200, 'PAYMENT_SUCCEEDED')]
        client, result = await self.flow(experiment, script)
        self.assertEqual('CONFIRMED', result['result'])
        self.assertEqual({'unavailable': 3}, dict(result['retries']))
        creates = [c for c in client.calls if c['path'] == '/api/v1/orders']
        self.assertEqual(3, len(creates))
        self.assertEqual({'the-key'}, {c['headers']['Idempotency-Key'] for c in creates})
        self.assertEqual(['/api/v1/orders/o-1/payment'] * 2, [c['path'] for c in client.calls if 'payment' in c['path']])
        self.assertEqual(3, len(experiment.waits))
        self.assertTrue(all(0.2 <= wait <= 1.2 for wait in experiment.waits))

    async def test_rate_limit_waits_retry_after_and_keeps_the_key(self):
        experiment = self.experiment()
        script = [reply(429, 'ORDER_RATE_LIMITED', None, **{'retry-after': '2'}),
                  reply(201, 'ORDER_CREATED', {'orderId': 'o-1'}), reply(200, 'PAYMENT_SUCCEEDED')]
        client, result = await self.flow(experiment, script)
        self.assertEqual([2.0], experiment.waits)
        self.assertEqual('CONFIRMED', result['result'])
        self.assertEqual(['the-key', 'the-key'], [c['headers']['Idempotency-Key'] for c in client.calls[:2]])

    async def test_lost_session_logs_in_again_and_repeats_the_same_key_without_recording_credentials(self):
        experiment = self.experiment()
        script = [reply(401, 'AUTHENTICATION_REQUIRED'), csrf_reply(), reply(200, 'LOGIN_SUCCEEDED'), csrf_reply(),
                  reply(201, 'ORDER_CREATED', {'orderId': 'o-1'}), reply(200, 'PAYMENT_SUCCEEDED')]
        client, result = await self.flow(experiment, script)
        self.assertEqual(1, result['relogins'])
        self.assertEqual(['POST', 'GET', 'POST', 'GET', 'POST', 'POST'], [c['method'] for c in client.calls])
        self.assertEqual('new-csrf', client.csrf)
        self.assertEqual({}, client.calls[1]['cookies'])
        creates = [c for c in client.calls if c['path'] == '/api/v1/orders']
        self.assertEqual({'the-key'}, {c['headers']['Idempotency-Key'] for c in creates})
        stored = json.dumps(experiment.attempts)
        for secret in ('secret-session', 'secret-csrf', 'new-session', 'new-csrf', 'pw-secret'):
            self.assertNotIn(secret, stored)

    async def test_csrf_failure_after_session_loss_is_handled_like_a_401(self):
        experiment = self.experiment()
        script = [reply(403, 'CSRF_INVALID'), csrf_reply(), reply(200, 'LOGIN_SUCCEEDED'), csrf_reply(),
                  reply(409, 'SALE_SOLD_OUT')]
        _, result = await self.flow(experiment, script)
        self.assertEqual('SOLD_OUT', result['result'])
        self.assertEqual(1, result['relogins'])

    async def test_unavailable_during_login_backs_off_and_starts_the_login_again(self):
        experiment = self.experiment()
        script = [reply(401, 'AUTHENTICATION_REQUIRED'), reply(503, 'AUTHENTICATION_UNAVAILABLE'), csrf_reply(),
                  reply(200, 'LOGIN_SUCCEEDED'), csrf_reply(), reply(409, 'SALE_SOLD_OUT')]
        client, result = await self.flow(experiment, script)
        self.assertEqual('SOLD_OUT', result['result'])
        self.assertEqual(1, len(experiment.waits))
        self.assertEqual('/api/v1/csrf', client.calls[2]['path'])

    async def test_sold_out_is_final_and_no_payment_is_sent(self):
        client, result = await self.flow(self.experiment(), [reply(409, 'SALE_SOLD_OUT')])
        self.assertEqual('SOLD_OUT', result['result'])
        self.assertEqual(1, len(client.calls))

    async def test_pending_payment_is_checked_again_after_a_second(self):
        experiment = self.experiment()
        script = [reply(201, 'ORDER_CREATED', {'orderId': 'o-1'}), reply(202, 'PAYMENT_PENDING'),
                  reply(200, 'PAYMENT_SUCCEEDED')]
        _, result = await self.flow(experiment, script)
        self.assertEqual(('CONFIRMED', [1.0]), (result['result'], experiment.waits))

    async def test_repeated_session_loss_gives_up_instead_of_looping(self):
        experiment = self.experiment()
        one_login = [csrf_reply(), reply(200, 'LOGIN_SUCCEEDED'), csrf_reply()]
        script = [reply(401, 'AUTHENTICATION_REQUIRED')]
        for _ in range(3):
            script += one_login + [reply(401, 'AUTHENTICATION_REQUIRED')]
        _, result = await self.flow(experiment, script)
        self.assertEqual('FAILED:RELOGIN_LOOP', result['result'])

    async def test_other_5xx_and_missing_answers_are_retried_and_recorded_for_the_5xx_criterion(self):
        experiment = self.experiment()

        class Failing(FakeClient):
            async def request_full(self, method, path, body=None, headers=None, timeout=35):
                if not self.script:
                    raise asyncio.TimeoutError()
                return await super().request_full(method, path, body, headers, timeout)
        client = Failing([reply(500, 'INTERNAL_ERROR'), reply(201, 'ORDER_CREATED', {'orderId': 'o-1'}),
                          reply(200, 'PAYMENT_SUCCEEDED')])
        result = {'user': 0, 'result': None, 'retries': __import__('collections').Counter(), 'relogins': 0,
                  'requests': 0}
        await experiment.run_user(0, client, 'the-key', result)
        self.assertEqual('CONFIRMED', result['result'])
        self.assertEqual({'other_5xx': 1, 'connection_errors': 0}, non_policy_failures(experiment.attempts))

    async def test_a_user_that_cannot_finish_before_the_deadline_is_reported(self):
        experiment = self.experiment()
        experiment.deadline.at = -1.0
        client, result = await self.flow(experiment, [])
        self.assertEqual('DEADLINE_EXCEEDED', result['result'])
        self.assertEqual([], client.calls)

    def test_deadline_is_open_until_the_outage_ends(self):
        deadline = Deadline(Clock())
        self.assertFalse(deadline.expired())
        deadline.at = deadline.clock.now() + 100
        self.assertFalse(deadline.expired())
        self.assertGreater(deadline.remaining(), 99)


if __name__ == '__main__':
    unittest.main()
