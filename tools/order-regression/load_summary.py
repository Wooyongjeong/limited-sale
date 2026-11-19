"""Pure logic of the 1-11 baseline run: scenarios, verifier input, pass criteria and cross-run aggregation."""
from dataclasses import dataclass
import statistics

EXPECTED_STATUSES = ('200', '201', '202', '409')
TERMINAL = ('CONFIRMED', 'PAYMENT_FAILED', 'EXPIRED')
ORDER_CREATE = ('POST', '/api/v1/orders')


@dataclass(frozen=True)
class Scenario:
    users: int
    stock: int
    vus: int

    @property
    def expected_confirmed(self):
        # Every normal request is served until the stock runs out; the rest are sold-out rejections.
        return min(self.users, self.stock)


SCENARIOS = {'baseline': Scenario(users=10_000, stock=1_000, vus=500),
             'under-demand': Scenario(users=800, stock=1_000, vus=500)}


def build_manifest(attempts, sale_id, scenario):
    """Fixed expectation for verify.py: one request per user, so created is exactly the confirmed count."""
    confirmed = scenario.expected_confirmed
    return {'sales': [{'id': sale_id, 'unit_price': 1000, 'total_quantity': scenario.stock, 'per_member_limit': 1}],
            'attempts': [{'attempt_id': a['id'], 'member_id': a['member_id'], 'key': a['key'], 'sale_id': sale_id,
                          'quantity': 1, 'allowed_results': ['CREATED', 'REJECTED'],
                          'rejection_reason': 'SALE_SOLD_OUT', 'final_statuses': ['CONFIRMED']} for a in attempts],
            'expected_results': {'CREATED': confirmed, 'REJECTED': len(attempts) - confirmed},
            'expected_order_statuses': {'CONFIRMED': confirmed}, 'recovery_required': False}


def build_requests(attempts, sale_id, request_results):
    """Per-attempt outcomes read from the stored request results, which the server writes for every decision.

    k6 does not keep one record per request, so an attempt with no stored result is reported as not sent.
    """
    stored = {(r['member_id'], r['key']): r for r in request_results}
    requests = []
    for attempt in attempts:
        row = stored.get((attempt['member_id'], attempt['key']))
        created = row is not None and row['outcome'] == 'CREATED'
        requests.append({'attempt_id': attempt['id'], 'member_id': attempt['member_id'], 'key': attempt['key'],
                         'sale_id': sale_id, 'quantity': 1, 'sent': row is not None,
                         'result': 'ERROR' if row is None else row['outcome'],
                         'order_id': row['order_id'] if created else None,
                         'rejection_reason': row['rejection_reason'] if row is not None and not created else None})
    return requests


def evaluate(result, scenario):
    """Return one dict per pass criterion: {'name', 'passed', 'expected', 'actual'}."""
    checks = []

    def check(name, expected, actual, passed=None):
        checks.append({'name': name, 'passed': expected == actual if passed is None else passed,
                       'expected': expected, 'actual': actual})

    k6, db = result['k6'], result['db']
    check('k6_exit_code', 0, k6['exit_code'])
    check('iterations_completed', scenario.users, k6['iterations_completed'])
    check('dropped_iterations', 0, k6['dropped_iterations'])
    check('server_errors_5xx', 0, k6['server_errors_5xx'])
    check('connection_errors', 0, k6['connection_errors'])
    unexpected = {status: count for status, count in k6['status_counts'].items() if status not in EXPECTED_STATUSES}
    check('unexpected_statuses', {}, unexpected)
    check('converged', True, db['converged'])
    check('confirmed', scenario.expected_confirmed, db['confirmed'])
    check('remaining', scenario.stock - scenario.expected_confirmed, db['remaining'])
    check('verifier_violations', 0, result['verifier_violations'])
    check('reconciliation_violations', 0, result['sql_violations'])
    codes = k6['business_codes']
    created = codes.get('ORDER_CREATED', 0)
    check('client_created_matches_orders', db['orders'], created)
    check('client_rejected_matches_expected', scenario.users - scenario.expected_confirmed,
          codes.get('SALE_SOLD_OUT', 0))
    check('payments_requested_for_every_created_order', created,
          sum(codes.get(code, 0) for code in ('PAYMENT_SUCCEEDED', 'PAYMENT_PENDING', 'PAYMENT_DECLINED',
                                              'ORDER_EXPIRED', 'ORDER_NOT_FOUND')))
    served = result['order_create_by_instance']
    check('every_order_request_reached_an_instance', scenario.users, sum(served.values()))
    check('mysql_observe_complete', True, result['mysql_observe_complete'])
    check('every_instance_served_orders', True, all(count > 0 for count in served.values()) and bool(served))
    return checks


def failures(checks):
    return [f"{c['name']}: expected {c['expected']} actual {c['actual']}" for c in checks if not c['passed']]


def _flatten(prefix, value, out):
    if isinstance(value, dict):
        for key, inner in value.items():
            _flatten(f'{prefix}.{key}' if prefix else str(key), inner, out)
    elif isinstance(value, (int, float)) and not isinstance(value, bool):
        out[prefix] = value


def headline(summary):
    """The numbers compared across runs, as a flat {name: number} map."""
    k6, db = summary['k6'], summary['db']
    selected = {
        'throughput': {'http_requests_per_second': k6['http_requests_per_second'],
                       'iterations_per_second': k6['iterations_per_second'],
                       'duration_seconds': k6['duration_seconds']},
        'latency_ms': {name: {key: values[key] for key in ('p50_ms', 'p95_ms', 'p99_ms', 'avg_ms', 'max_ms')}
                       for name, values in k6['latency'].items()},
        'errors': {'server_errors_5xx': k6['server_errors_5xx'], 'connection_errors': k6['connection_errors']},
        'status_counts': k6['status_counts'], 'business_codes': k6['business_codes'],
        'database': {'confirmed': db['confirmed'], 'remaining': db['remaining'], 'orders': db['orders']},
        'management': {key: value for key, value in _management_headline(summary['management']['combined']).items()},
        'mysql': {key: summary['mysql'][key] for key in
                  ('row_lock_waits', 'row_lock_time_ms', 'deadlocks', 'lock_timeouts')},
        'load_generator': summary['load_generator'],
        'durations_seconds': summary['durations_seconds'],
    }
    flat = {}
    _flatten('', selected, flat)
    return flat


def _management_headline(combined):
    hikari, jvm = combined['hikari'], combined['jvm']
    return {'hikari_active_peak': hikari['active_peak'], 'hikari_pending_peak': hikari['pending_peak'],
            'hikari_acquire_mean_ms': hikari['acquire_mean_ms'], 'hikari_acquire_max_ms_peak': hikari['acquire_max_ms_peak'],
            'jvm_cpu_seconds': jvm['cpu_seconds'], 'system_cpu_usage_mean': jvm['system_cpu_usage_mean'],
            'process_cpu_usage_peak': jvm['process_cpu_usage_peak'], 'system_cpu_usage_peak': jvm['system_cpu_usage_peak'],
            'gc_pause_seconds': jvm['gc_pause_seconds'], 'tomcat_threads_busy_peak': jvm['tomcat_threads_busy_peak']}


def aggregate(summaries):
    """Median, minimum and maximum of every headline number across runs; a run lacking a number is skipped."""
    flats = [headline(summary) for summary in summaries]
    metrics = {}
    for name in sorted({key for flat in flats for key in flat}):
        values = [flat[name] for flat in flats if flat.get(name) is not None]
        metrics[name] = {'median': statistics.median(values), 'min': min(values), 'max': max(values),
                         'values': values}
    return metrics
