"""Pure logic of the 2-6 comparison runs: conditions, verifier input with abusive users, pass criteria, aggregation.

Four conditions isolate one switch each. Experiment A reads the sale detail five times before every order, with the
sale cache off or on and the order request limit off. Experiment B adds abusive users who hammer order creation
next to the normal 1-11 flow, with the request limit off or on and the cache at its default (on).

Experiment C (stage 2-7) is the plain 1-11 flow at the application defaults for cache and request limit, with the
Redis sold-out pre-check off or on. The gate switch is passed to every run; the older conditions were measured
before the gate existed, so they keep it off and stay comparable.
"""
from dataclasses import dataclass, replace
import math
import statistics

from load_summary import _flatten, _management_headline, build_requests

BUSINESS_STATUSES = ('200', '201', '202', '409', '429')
PAYMENT_CODES = ('PAYMENT_SUCCEEDED', 'PAYMENT_PENDING', 'PAYMENT_DECLINED', 'ORDER_EXPIRED', 'ORDER_NOT_FOUND')
ABUSE_REJECTIONS = ['SALE_SOLD_OUT', 'PURCHASE_LIMIT_EXCEEDED']


@dataclass(frozen=True)
class Condition:
    name: str
    experiment: str
    sale_cache: bool
    order_rate_limit: bool
    detail_views: int
    abusers: int
    abuser_vus_per_member: int = 1
    # Always passed to the sales JVMs, so a result keeps its condition when the application default changes: 5 was
    # the default when a, b and b2 were measured; b3 uses one request per window.
    rate_limit_max_requests: int = 5
    # True for the condition whose limit is low enough that abusive members must be rejected at least once.
    expects_rejections: bool = False
    # The Redis sold-out pre-check. Off for every condition measured before stage 2-7 so they keep their meaning.
    sold_out_gate: bool = False
    # True for the condition whose over-demand must be turned away by the marker at least once.
    expects_gate_hits: bool = False
    # Stage 3-8: 'v1' orders through POST /api/v1/orders, 'v2' accepts through POST /api/v2/orders, polls and pays.
    api: str = 'v1'


@dataclass(frozen=True)
class Plan:
    users: int
    stock: int
    vus: int
    detail_views: int
    abusers: int
    abuser_vus_per_member: int = 1

    @property
    def expected_confirmed(self):
        # Every order that is created is paid by a Mock PG that always succeeds, so demand above the stock sells it out.
        return min(self.users + self.abusers, self.stock)


# Experiment A: 5 reads per order, the 1-11 baseline otherwise. Experiment B: 100 abusive users beside it.
CONDITIONS = {
    'a-off': Condition('a-off', 'A', sale_cache=False, order_rate_limit=False, detail_views=5, abusers=0),
    'a-on': Condition('a-on', 'A', sale_cache=True, order_rate_limit=False, detail_views=5, abusers=0),
    'b-off': Condition('b-off', 'B', sale_cache=True, order_rate_limit=False, detail_views=0, abusers=100),
    'b-on': Condition('b-on', 'B', sale_cache=True, order_rate_limit=True, detail_views=0, abusers=100),
    # Experiment B redesigned: every abusive member sends five requests at once from the same session.
    'b2-off': Condition('b2-off', 'B', sale_cache=True, order_rate_limit=False, detail_views=0, abusers=100,
                        abuser_vus_per_member=5),
    'b2-on': Condition('b2-on', 'B', sale_cache=True, order_rate_limit=True, detail_views=0, abusers=100,
                       abuser_vus_per_member=5),
}
# Experiment B with a one-request-per-second limit (b2's abusers send only about 1.6 per second, under the default 5).
# b3-off passes the same value although the switch is off, so both conditions record the same environment.
CONDITIONS['b3-off'] = Condition('b3-off', 'B', sale_cache=True, order_rate_limit=False, detail_views=0, abusers=100,
                                 abuser_vus_per_member=5, rate_limit_max_requests=1)
CONDITIONS['b3-on'] = Condition('b3-on', 'B', sale_cache=True, order_rate_limit=True, detail_views=0, abusers=100,
                                abuser_vus_per_member=5, rate_limit_max_requests=1, expects_rejections=True)
# Experiment C (stage 2-7): 10,000 normal users for 1,000 units, cache on, request limit at the application default
# (on, one per second), no reads and no abusers. Only the sold-out pre-check differs.
CONDITIONS['c-off'] = Condition('c-off', 'C', sale_cache=True, order_rate_limit=True, detail_views=0, abusers=0,
                                rate_limit_max_requests=1, sold_out_gate=False)
CONDITIONS['c-on'] = Condition('c-on', 'C', sale_cache=True, order_rate_limit=True, detail_views=0, abusers=0,
                               rate_limit_max_requests=1, sold_out_gate=True, expects_gate_hits=True)
# Stage 3-8: the c-on settings (cache on, request limit one per second, sold-out pre-check on), one API each. The same JAR
# runs both; the v1 condition simply never calls the v2 API.
CONDITIONS['v1-sync'] = Condition('v1-sync', 'D', sale_cache=True, order_rate_limit=True, detail_views=0, abusers=0,
                                  rate_limit_max_requests=1, sold_out_gate=True, expects_gate_hits=True)
CONDITIONS['v2-async'] = Condition('v2-async', 'D', sale_cache=True, order_rate_limit=True, detail_views=0, abusers=0,
                                   rate_limit_max_requests=1, sold_out_gate=True, expects_gate_hits=True, api='v2')
DEFAULT_CONDITIONS = ('a-off', 'a-on', 'b-off', 'b-on')
# Every acceptance must be final this long after the load ended (3-1 #8).
FINAL_DEADLINE_SECONDS = 300


def policy_plan(condition):
    return Plan(users=10_000, stock=1_000, vus=500, detail_views=condition.detail_views, abusers=condition.abusers,
                abuser_vus_per_member=condition.abuser_vus_per_member)


def with_overrides(plan, users=None, stock=None, vus=None, detail_views=None, abusers=None,
                   abuser_vus_per_member=None):
    """Smoke-test overrides; reads and abusers belong to one experiment each, so a zero in the plan stays zero."""
    return replace(plan, users=users or plan.users, stock=stock or plan.stock, vus=vus or plan.vus,
                   detail_views=detail_views if detail_views is not None and plan.detail_views else plan.detail_views,
                   abusers=abusers if abusers is not None and plan.abusers else plan.abusers,
                   abuser_vus_per_member=(abuser_vus_per_member if abuser_vus_per_member is not None and plan.abusers
                                          else plan.abuser_vus_per_member))


def execution_order(conditions, rounds):
    """Conditions interleaved round by round, so slow drift of the machine does not favour one condition."""
    return [(index, name) for index in range(1, rounds + 1) for name in conditions]


# ---- verifier input ----

def abuse_attempts(abuser_ids, request_results):
    """One attempt per stored request result of an abusive user.

    The abusive keys are random and k6 keeps no per-request record, so the server's order_request_results are the
    only list of what reached the application. A 429 is not recorded by design, so it is absent here.
    """
    return [{'id': f"abuse-{row['member_id']}-{row['key']}", 'member_id': row['member_id'], 'key': row['key']}
            for row in request_results if row['member_id'] in abuser_ids]


def build_manifest(normal, abuse, sale_id, plan):
    """Fixed expectation for verify.py. Normal users have one planned request each; abusive ones come from the server."""
    confirmed = plan.expected_confirmed
    planned = []
    for reason, attempts in (('SALE_SOLD_OUT', normal), (ABUSE_REJECTIONS, abuse)):
        planned += [{'attempt_id': a['id'], 'member_id': a['member_id'], 'key': a['key'], 'sale_id': sale_id,
                     'quantity': 1, 'allowed_results': ['CREATED', 'REJECTED'], 'rejection_reason': reason,
                     'final_statuses': ['CONFIRMED']} for a in attempts]
    return {'sales': [{'id': sale_id, 'unit_price': 1000, 'total_quantity': plan.stock, 'per_member_limit': 1}],
            'attempts': planned,
            # Rejections: at least the normal users beyond the stock, at most every other recorded attempt.
            'expected_results': {'CREATED': confirmed,
                                 'REJECTED': [max(len(normal) - confirmed, 0), len(planned) - confirmed]},
            'expected_order_statuses': {'CONFIRMED': confirmed}, 'recovery_required': False}


def verifier_requests(normal, abuse, sale_id, request_results):
    return build_requests([*normal, *abuse], sale_id, request_results)


def abuse_server_summary(abuser_ids, request_results, orders):
    """What the server stored for the abusive users, and how many of their orders ended confirmed."""
    mine = [row for row in request_results if row['member_id'] in abuser_ids]
    rejections = {}
    for row in mine:
        if row['outcome'] != 'CREATED':
            rejections[row['rejection_reason']] = rejections.get(row['rejection_reason'], 0) + 1
    return {'stored_requests': len(mine), 'created': sum(row['outcome'] == 'CREATED' for row in mine),
            'rejections': rejections,
            'confirmed': sum(o['status'] == 'CONFIRMED' and o['member_id'] in abuser_ids for o in orders)}


# ---- Prometheus counters ----

def labeled_deltas(before, after, name):
    """Counter increase per label set, from two parse_prometheus results of the same instance."""
    return {pairs: value - before.get((series, pairs), 0.0) for (series, pairs), value in after.items()
            if series == name}


def gate_counts(deltas_per_instance):
    """{result: increase} summed over instances, from sale_sold_out_gate_requests_total."""
    counts = {'hit': 0, 'miss': 0, 'error': 0}
    for deltas in deltas_per_instance:
        for pairs, value in deltas.items():
            counts[dict(pairs)['result']] += int(value)
    return counts


def gate_update_counts(deltas_per_instance):
    """{operation: {result: increase}} summed over instances, from sale_sold_out_gate_updates_total."""
    counts = {operation: {'success': 0, 'error': 0} for operation in ('mark', 'clear')}
    for deltas in deltas_per_instance:
        for pairs, value in deltas.items():
            labels = dict(pairs)
            counts[labels['operation']][labels['result']] += int(value)
    return counts


def rate_limit_counts(deltas_per_instance):
    """{operation: {result: increase}} summed over instances, from order_rate_limit_total."""
    counts = {operation: {'allowed': 0, 'rejected': 0, 'bypassed': 0} for operation in ('create', 'payment')}
    for deltas in deltas_per_instance:
        for pairs, value in deltas.items():
            labels = dict(pairs)
            counts[labels['operation']][labels['result']] += int(value)
    return counts


def cache_counts(deltas_per_instance):
    """{result: increase} summed over instances, from sale_cache_requests_total."""
    counts = {'hit': 0, 'miss': 0, 'error': 0}
    for deltas in deltas_per_instance:
        for pairs, value in deltas.items():
            counts[dict(pairs)['result']] += int(value)
    return counts


def hit_rate(counts):
    lookups = counts['hit'] + counts['miss']
    return round(counts['hit'] / lookups, 4) if lookups else None


def rejection_rate(rejected, total):
    return round(rejected / total, 4) if total else None


# ---- pass criteria ----

def status_total(endpoint, status):
    return endpoint['status_counts'].get(status, 0)


def evaluate(result, condition, plan):
    """One dict per pass criterion: {'name', 'passed', 'expected', 'actual'}."""
    checks = []

    def check(name, expected, actual, passed=None):
        checks.append({'name': name, 'passed': expected == actual if passed is None else passed,
                       'expected': expected, 'actual': actual})

    k6, db = result['k6'], result['db']
    endpoints = k6['endpoints']
    create, payment = endpoints['order_create'], endpoints['order_payment']
    abuse_create, abuse_payment = endpoints['abuse_order_create'], endpoints['abuse_order_payment']
    detail = endpoints['sale_detail']
    check('k6_exit_code', 0, k6['exit_code'])
    check('iterations_completed', plan.users, k6['iterations_completed'])
    check('dropped_iterations', 0, k6['dropped_iterations'])
    check('server_errors_5xx', 0, k6['server_errors_5xx'])
    check('connection_errors', 0, k6['connection_errors'])
    unexpected = {}
    for name, endpoint in endpoints.items():
        for status, count in endpoint['status_counts'].items():
            if status not in BUSINESS_STATUSES:
                unexpected[f'{name}:{status}'] = count
    check('unexpected_statuses', {}, unexpected)
    check('converged', True, db['converged'])
    check('confirmed', plan.expected_confirmed, db['confirmed'])
    check('remaining', plan.stock - plan.expected_confirmed, db['remaining'])
    check('verifier_violations', 0, result['verifier_violations'])
    check('reconciliation_violations', 0, result['sql_violations'])

    created = create['business_codes'].get('ORDER_CREATED', 0)
    abuse_created = abuse_create['business_codes'].get('ORDER_CREATED', 0)
    check('client_created_matches_orders', db['orders'], created + abuse_created)
    check('normal_users_each_got_a_create_response', plan.users, create['count'])
    check('payments_requested_for_every_created_order', [created, abuse_created],
          [payment['count'], abuse_payment['count']])
    check('abusers_ran_only_when_planned', plan.abusers > 0, abuse_create['count'] > 0)
    check('abuser_vus_per_member_as_planned', plan.abuser_vus_per_member if plan.abusers else None,
          k6.get('abuser_vus_per_member') if plan.abusers else None)

    create_requests = create['count'] + abuse_create['count']
    check('every_order_request_reached_an_instance', create_requests, sum(result['order_create_by_instance'].values()))
    check('every_instance_served_orders', True,
          all(count > 0 for count in result['order_create_by_instance'].values())
          and bool(result['order_create_by_instance']))
    check('mysql_observe_complete', True, result['mysql_observe_complete'])
    # The disabled cache registers no metric at all, so the series' presence shows which switch the JVMs ran with.
    check('sale_cache_setting_took_effect', condition.sale_cache, result['cache_metrics_present'])
    check('sold_out_gate_setting_took_effect', condition.sold_out_gate, result['gate_metrics_present'])

    # A rate-limited request is not recorded, so the stored results are the responses that were not 429.
    rate_limited_create = status_total(create, '429') + status_total(abuse_create, '429')
    check('server_stored_every_create_that_was_not_rate_limited', create_requests - rate_limited_create,
          db['request_results'])
    if plan.abusers:
        check('abuse_server_records_match_client', abuse_create['count'] - status_total(abuse_create, '429'),
              result['abuse']['stored_requests'])
        check('abuse_created_matches_server', abuse_created, result['abuse']['created'])
    check('detail_reads_all_200', {'200': plan.users * plan.detail_views} if plan.detail_views else {},
          detail['status_counts'])

    limit = result['rate_limit']
    rate_limited_payment = status_total(payment, '429') + status_total(abuse_payment, '429')
    check('rate_limit_bypassed', 0, limit['create']['bypassed'] + limit['payment']['bypassed'])
    check('client_429_matches_server_rejections', [rate_limited_create, rate_limited_payment],
          [limit['create']['rejected'], limit['payment']['rejected']])
    # Normal users send one order and one payment, counted separately, so even a limit of one never rejects them.
    check('normal_users_never_rate_limited', 0, status_total(create, '429') + status_total(payment, '429'))
    if condition.order_rate_limit:
        check('every_create_was_counted_by_the_limit', create_requests,
              limit['create']['allowed'] + limit['create']['rejected'])
        if condition.expects_rejections:
            check('rate_limit_override_reached_the_jvms', True, limit['create']['rejected'] > 0)
    else:
        check('rate_limit_off_counts_nothing', 0, sum(sum(row.values()) for row in limit.values()))

    gate, gate_updates = result['gate'], result['gate_updates']
    lookups_by_gate = gate['hit'] + gate['miss'] + gate['error']
    if condition.sold_out_gate:
        # Every create that was not answered 429 asks the gate exactly once, before any transaction.
        check('every_create_was_a_gate_lookup', create_requests - rate_limited_create, lookups_by_gate)
        check('gate_lookup_errors', 0, gate['error'])
        check('gate_update_errors', 0, gate_updates['mark']['error'] + gate_updates['clear']['error'])
        if condition.expects_gate_hits:
            check('gate_turned_requests_away', True, gate['hit'] > 0)
    else:
        check('no_gate_lookups_or_updates', 0, lookups_by_gate
              + sum(sum(row.values()) for row in gate_updates.values()))

    cache = result['cache']
    lookups = cache['hit'] + cache['miss'] + cache['error']
    if condition.sale_cache and plan.detail_views:
        check('every_detail_read_was_a_cache_lookup', detail['count'], lookups)
        check('cache_errors', 0, cache['error'])
    else:
        check('no_cache_lookups', 0, lookups)
    return checks


def count_inversions(values):
    """Pairs i < j with values[i] > values[j], by merge sort (the runs have 10,000 acceptances)."""
    def sort(items):
        if len(items) <= 1:
            return items, 0
        middle = len(items) // 2
        left, a = sort(items[:middle])
        right, b = sort(items[middle:])
        merged, inversions, i, j = [], a + b, 0, 0
        while i < len(left) and j < len(right):
            if left[i] <= right[j]:
                merged.append(left[i])
                i += 1
            else:
                merged.append(right[j])
                inversions += len(left) - i
                j += 1
        merged += left[i:] + right[j:]
        return merged, inversions
    return sort(list(values))[1]


def percentile(values, fraction):
    """Nearest-rank percentile of a list; None when it is empty."""
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, min(len(ordered) - 1, math.ceil(fraction * len(ordered)) - 1))]


def async_db_summary(acceptances):
    """What the database says about the v2 run, from one row per acceptance of the sale.

    Each row: id, status, rejection_reason, failure_reason, consumed (it had an outbox row, so the consumer decided it),
    completion_ms (finished_at - accepted_at, None while unfinished), orders (purchase orders with its id as key),
    sends (outbox rows). The processing order is the finish time; ties keep the id order.
    """
    by_status = {}
    reasons = {}
    for row in acceptances:
        by_status[row['status']] = by_status.get(row['status'], 0) + 1
        reason = row.get('rejection_reason') or row.get('failure_reason')
        if reason:
            reasons[f"{row['status']}:{reason}"] = reasons.get(f"{row['status']}:{reason}", 0) + 1
    consumed = sorted((r for r in acceptances if r['consumed'] and r['completion_ms'] is not None),
                      key=lambda r: r['id'])
    order = [r['finished_at_us'] for r in consumed]
    created_ids = [r['id'] for r in consumed if r['status'] == 'ORDER_CREATED']
    last_created = max(created_ids) if created_ids else None
    # A request that lost to a later one: rejected as sold out although an acceptance after it got an order.
    overtaken = sum(1 for r in consumed if r['status'] == 'REJECTED' and r.get('rejection_reason') == 'SALE_SOLD_OUT'
                    and last_created is not None and r['id'] < last_created)
    completion = [r['completion_ms'] for r in consumed if r['status'] == 'ORDER_CREATED']
    return {
        'acceptances': len(acceptances), 'by_status': by_status, 'reasons': reasons,
        'consumed': len(consumed), 'unfinished': by_status.get('ACCEPTED', 0),
        'processing_failed': by_status.get('PROCESSING_FAILED', 0),
        'acceptances_with_more_than_one_order': sum(1 for r in acceptances if r['orders'] > 1),
        'republished_acceptances': sum(1 for r in acceptances if r['sends'] > 1),
        'order_inversions': count_inversions(order),
        'sold_out_before_a_later_order': overtaken,
        'completion_ms': {'count': len(completion), 'p50': percentile(completion, 0.5),
                          'p95': percentile(completion, 0.95), 'p99': percentile(completion, 0.99),
                          'max': max(completion) if completion else None},
    }


def evaluate_async(result, condition, plan, fault=None):
    """Pass criteria of the v2 condition (3-8 #7); a fault run (3-8 #8) tolerates answered 5xx and its own outcome."""
    checks = []

    def check(name, expected, actual, passed=None):
        checks.append({'name': name, 'passed': expected == actual if passed is None else passed,
                       'expected': expected, 'actual': actual})

    k6, db, store = result['k6'], result['db'], result['async']
    create, payment = k6['endpoints']['order_create'], k6['endpoints']['order_payment']
    check('k6_exit_code', 0, k6['exit_code'])
    check('iterations_completed', plan.users, k6['iterations_completed'])
    check('dropped_iterations', 0, k6['dropped_iterations'])
    check('unresolved_requests', 0, k6['unresolved_requests'])
    if fault is None:
        check('server_errors_5xx', 0, k6['server_errors_5xx'])
        check('connection_errors', 0, k6['connection_errors'])
        unexpected = {f'{name}:{status}': count for name, endpoint in k6['endpoints'].items()
                      for status, count in endpoint['status_counts'].items() if status not in BUSINESS_STATUSES}
        check('unexpected_statuses', {}, unexpected)
    check('all_final_within_deadline', True, result['all_final_within_deadline'])
    check('no_unfinished_acceptances', 0, store['unfinished'])
    check('no_processing_failures', 0, store['processing_failed'])
    check('at_most_one_order_per_acceptance', 0, store['acceptances_with_more_than_one_order'])
    check('client_never_hit_the_poll_deadline', 0, k6['finals'].get('POLL_DEADLINE', 0))
    rate_limited = status_total(create, '429')
    check('normal_users_never_rate_limited', 0, rate_limited + status_total(payment, '429'))
    check('one_acceptance_per_user', plan.users - rate_limited, store['acceptances'])
    expected_confirmed = result.get('expected_confirmed', plan.expected_confirmed)
    check('converged', True, db['converged'])
    check('confirmed', expected_confirmed, db['confirmed'])
    check('remaining', plan.stock - expected_confirmed, db['remaining'])
    check('orders_match_order_created', db['orders'], store['by_status'].get('ORDER_CREATED', 0))
    check('client_saw_every_order', db['orders'], k6['finals'].get('ORDER_CREATED', 0))
    if fault is None:
        check('every_order_paid', db['orders'], payment['business_codes'].get('PAYMENT_SUCCEEDED', 0))
    else:
        # A payment retried after a killed instance may be answered 202 (still pending); 'confirmed' shows it ended.
        check('every_order_got_a_payment_answer', db['orders'],
              payment['business_codes'].get('PAYMENT_SUCCEEDED', 0) + payment['business_codes'].get('PAYMENT_PENDING', 0))
    check('reconciliation_violations', 0, result['sql_violations'])
    check('mysql_observe_complete', True, result['mysql_observe_complete'])
    check('sale_cache_setting_took_effect', condition.sale_cache, result['cache_metrics_present'])
    check('sold_out_gate_setting_took_effect', condition.sold_out_gate, result['gate_metrics_present'])
    if fault is None:
        gate = result['gate']
        check('every_acceptance_was_a_gate_lookup', create['count'] - rate_limited,
              gate['hit'] + gate['miss'] + gate['error'])
        check('gate_lookup_errors', 0, gate['error'])
        check('every_instance_served_acceptances', True,
              bool(result['order_create_by_instance'])
              and all(count > 0 for count in result['order_create_by_instance'].values()))
        # The servers' completion histogram saw exactly the acceptances the consumer decided.
        check('completion_histogram_matches_database', store['consumed'], result['completion_recorded'])
    return checks


def failures(checks):
    return [f"{c['name']}: expected {c['expected']} actual {c['actual']}" for c in checks if not c['passed']]


# ---- cross-run numbers ----

def headline(summary):
    """The numbers compared across runs, as a flat {name: number} map."""
    k6, db = summary['k6'], summary['db']
    latency = {name: {key: values[key] for key in ('count', 'p50_ms', 'p95_ms', 'p99_ms', 'avg_ms', 'max_ms')}
               for name, values in k6['endpoints'].items() if values['count']}
    selected = {
        'throughput': {'http_requests_per_second': k6['http_requests_per_second'],
                       'duration_seconds': k6['duration_seconds'],
                       'normal_duration_seconds': k6.get('normal_duration_seconds')},
        'derived': summary['derived'],
        'endpoints': latency,
        'status_counts': {name: values['status_counts'] for name, values in k6['endpoints'].items()
                          if values['status_counts']},
        'business_codes': {name: values['business_codes'] for name, values in k6['endpoints'].items()
                           if values['business_codes']},
        'errors': {'server_errors_5xx': k6['server_errors_5xx'], 'connection_errors': k6['connection_errors']},
        'database': {key: db[key] for key in ('confirmed', 'remaining', 'orders', 'confirmed_normal',
                                              'confirmed_abuse')},
        'cache': summary['cache'], 'rate_limit': summary['rate_limit'], 'abuse': summary['abuse'],
        'gate': summary.get('gate', {}), 'gate_updates': summary.get('gate_updates', {}),
        'latency_by_code': {code: {key: values[key] for key in ('count', 'p50_ms', 'p95_ms', 'p99_ms', 'avg_ms',
                                                                 'max_ms')}
                            for code, values in k6.get('latency_by_code', {}).items() if values['count']},
        'management': _management_headline(summary['management']['combined']),
        'mysql': {key: summary['mysql'][key] for key in
                  ('row_lock_waits', 'row_lock_time_ms', 'deadlocks', 'lock_timeouts')},
        'load_generator': summary['load_generator'],
        'durations_seconds': summary['durations_seconds'],
        # Client time from the order request until the payment succeeded, both flows (stage 3-8).
        'request_to_confirmed': {key: k6.get('request_to_confirmed', {}).get(key)
                                 for key in ('count', 'p50_ms', 'p95_ms', 'p99_ms', 'max_ms')},
    }
    if summary.get('async'):
        store = summary['async']
        selected['async'] = {
            'acceptances': store['acceptances'], 'order_inversions': store['order_inversions'],
            'sold_out_before_a_later_order': store['sold_out_before_a_later_order'],
            'republished_acceptances': store['republished_acceptances'],
            'completion_ms': store['completion_ms'], 'final_seconds_after_load': summary['final_seconds_after_load'],
            'acceptance_window_seconds': store.get('acceptance_window_seconds'),
            'acceptances_per_second': store.get('acceptances_per_second'),
            'accept_to_final': {key: k6['accept_to_final'].get(key) for key in ('p50_ms', 'p95_ms', 'p99_ms', 'max_ms')},
            'polls_per_acceptance': k6['polls_per_acceptance'],
            'status_polls': k6['endpoints']['order_status']['count'],
        }
    flat = {}
    _flatten('', selected, flat)
    return flat


def derived(k6, db, cache, rate_limit, abusers=0, gate=None):
    """Ratios the comparison reports; each is None when its denominator is zero."""
    endpoints = k6['endpoints']
    duration = k6['duration_seconds']
    normal = k6.get('normal_duration_seconds')
    abuse_create = endpoints['abuse_order_create']
    limit = rate_limit['create']
    decided = limit['allowed'] + limit['rejected'] + limit['bypassed']
    return {
        'normal_rate_limited_responses': status_total(endpoints['order_create'], '429')
        + status_total(endpoints['order_payment'], '429'),
        'actual_order_throughput_per_second': round(db['confirmed'] / duration, 3) if duration else None,
        'actual_order_throughput_per_normal_second': round(db['confirmed'] / normal, 3) if normal else None,
        'detail_requests_per_second': round(endpoints['sale_detail']['count'] / duration, 3) if duration else None,
        'cache_hit_rate': hit_rate(cache),
        'gate_hit_rate': hit_rate(gate) if gate else None,
        'abuse_requests_per_second': round(abuse_create['count'] / duration, 3) if duration else None,
        # Attempts per abusive member per second, over the time the abusers ran (the normal scenario's length).
        'abuse_requests_per_member_per_second': round(abuse_create['count'] / abusers / normal, 3)
        if abusers and normal else None,
        'abuse_rejection_rate': rejection_rate(status_total(abuse_create, '429'), abuse_create['count']),
        'create_rejection_rate_by_server': rejection_rate(limit['rejected'], decided),
    }


def aggregate(summaries):
    """Median, minimum and maximum of every headline number across runs; a run lacking a number is skipped."""
    flats = [headline(summary) for summary in summaries]
    metrics = {}
    for name in sorted({key for flat in flats for key in flat}):
        values = [flat[name] for flat in flats if flat.get(name) is not None]
        metrics[name] = {'median': statistics.median(values), 'min': min(values), 'max': max(values),
                         'values': values}
    return metrics
