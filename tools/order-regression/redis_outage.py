"""Pure logic of the stage 2-5 Redis outage run: the client retry state machine, outage phases, latency statistics,
cache-versus-database comparison, server-record cross-checks and pass criteria.

Everything here works on plain dictionaries so it can be unit-tested without Docker or a running application.
Times are seconds on one monotonic clock whose zero is the start of the load.
"""
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal
import json
import math
import random
import re

PHASES = ('before', 'during', 'after')
UNAVAILABLE_CODE = 'AUTHENTICATION_UNAVAILABLE'
SESSION_LOST_STATUS = 401
CSRF_INVALID_CODE = 'CSRF_INVALID'
CREATE_CREATED_CODES = ('ORDER_CREATED', 'ORDER_FOUND')
DEFAULT_RATE_LIMIT_WAIT_SECONDS = 1.0
PAYMENT_PENDING_RECHECK_SECONDS = 1.0
MAX_RELOGINS_PER_REQUEST = 3
TIMEOUT_PATTERN = re.compile(r'^(\d+)(ms|s)$')
# The application stores sales detail JSON under this key with this time to live (limited-sale.sale.cache.ttl).
CACHE_TTL_SECONDS = 60


def parse_timeout_seconds(text):
    """Seconds of a Spring duration written with an explicit unit; a bare number would mean milliseconds."""
    found = TIMEOUT_PATTERN.match(text or '')
    if not found:
        raise ValueError(f'timeout must look like 1s or 500ms, got {text!r}')
    value = int(found.group(1))
    if value <= 0:
        raise ValueError('timeout must be positive')
    return value / 1000 if found.group(2) == 'ms' else float(value)


def timeout_label(text):
    """Directory-friendly name such as timeout-1s."""
    parse_timeout_seconds(text)
    return f'timeout-{text}'


# ---- client retry state machine ----

@dataclass(frozen=True)
class Outcome:
    status: object = None
    code: object = None
    retry_after: object = None
    error: object = None


@dataclass(frozen=True)
class Decision:
    """kind: proceed | finish | retry | relogin | fail. wait is None for the backoff schedule, else seconds."""
    kind: str
    wait: object = None
    result: object = None
    reason: object = None


def classify(outcome):
    """Name the response class used for counting and for the 5xx criterion."""
    status, code = outcome.status, outcome.code
    if outcome.error is not None or status is None:
        return 'connection_error'
    if status == 503 and code == UNAVAILABLE_CODE:
        return 'auth_unavailable'
    if status >= 500:
        return 'other_5xx'
    if status == 429:
        return 'rate_limited'
    if status == SESSION_LOST_STATUS or (status == 403 and code == CSRF_INVALID_CODE):
        return 'session_rejected'
    if 200 <= status < 300 or status in (404, 409):
        return 'business'
    return 'client_error'


def _transient(outcome, kind):
    """Responses every step retries with the same request key: no answer, 5xx and rate limiting."""
    if outcome.error is not None or outcome.status is None:
        return Decision('retry', None, reason='connection_error')
    if outcome.status == 429:
        wait = outcome.retry_after if outcome.retry_after is not None else DEFAULT_RATE_LIMIT_WAIT_SECONDS
        return Decision('retry', wait, reason='rate_limited')
    if outcome.status == 503:
        return Decision('retry', None, reason='unavailable')
    if outcome.status >= 500:
        return Decision('retry', None, reason='server_error')
    return None


def decide(step, outcome):
    """What the client does after one response.

    step is create (POST /orders), payment (POST /orders/{id}/payment), csrf (GET /csrf) or login (POST /sessions).
    A 401 means the session is gone, so the client logs in again and repeats the same request with the same
    Idempotency-Key. A CSRF failure is treated the same because the CSRF token lives in the lost session.
    503 waits a short backoff and 429 waits Retry-After, each repeating the same key. A business rejection such as
    sold out is the final result of the user.
    """
    transient = _transient(outcome, step)
    if transient is not None:
        return transient
    status, code = outcome.status, outcome.code
    if step == 'csrf':
        return Decision('proceed') if status == 200 else Decision('fail', result=f'UNEXPECTED:{status}:{code}')
    if step == 'login':
        if status == 200:
            return Decision('proceed')
        if status == 403 and code == CSRF_INVALID_CODE:
            return Decision('retry', None, reason='csrf_invalid_during_login')
        return Decision('fail', result=f'LOGIN_FAILED:{status}:{code}')
    if status == SESSION_LOST_STATUS or (status == 403 and code == CSRF_INVALID_CODE):
        return Decision('relogin', reason='session_lost')
    if step == 'create':
        if status in (200, 201) and code in CREATE_CREATED_CODES:
            return Decision('proceed')
        if status in (404, 409):
            return Decision('finish', result='SOLD_OUT' if code == 'SALE_SOLD_OUT' else f'REJECTED:{code}')
    elif step == 'payment':
        if status == 200 and code == 'PAYMENT_SUCCEEDED':
            return Decision('finish', result='CONFIRMED')
        if status == 202:
            return Decision('retry', PAYMENT_PENDING_RECHECK_SECONDS, reason='payment_pending')
        if status in (404, 409):
            return Decision('finish', result='PAYMENT_DECLINED' if code == 'PAYMENT_DECLINED' else f'REJECTED:{code}')
    return Decision('fail', result=f'UNEXPECTED:{status}:{code}')


class Backoff:
    """Short exponential backoff with jitter for 503; the generator is seeded so a run can be replayed."""

    def __init__(self, seed, base=0.25, factor=2.0, cap=1.0, jitter=0.2):
        self.random = random.Random(seed)
        self.base, self.factor, self.cap, self.jitter = base, factor, cap, jitter

    def delay(self, streak):
        raw = min(self.cap, self.base * self.factor ** max(0, streak - 1))
        return raw * (1 + self.random.uniform(-self.jitter, self.jitter))


def parse_retry_after(value):
    try:
        seconds = float(value)
    except (TypeError, ValueError):
        return None
    return seconds if seconds >= 0 else None


# ---- phases and statistics ----

def phase_of(finished_s, outage):
    """Phase of a response by its completion time: before the stop command, while Redis is down, after the start."""
    if finished_s < outage['stop_issued_s']:
        return 'before'
    if finished_s < outage['start_returned_s']:
        return 'during'
    return 'after'


def percentile(sorted_values, fraction):
    """Nearest-rank percentile of an ascending list."""
    if not sorted_values:
        return None
    return sorted_values[max(0, math.ceil(fraction * len(sorted_values)) - 1)]


def distribution(values):
    ordered = sorted(values)
    if not ordered:
        return {'count': 0, 'p50_ms': None, 'p95_ms': None, 'p99_ms': None, 'max_ms': None}
    return {'count': len(ordered), 'p50_ms': round(percentile(ordered, .50), 1),
            'p95_ms': round(percentile(ordered, .95), 1), 'p99_ms': round(percentile(ordered, .99), 1),
            'max_ms': round(ordered[-1], 1)}


def label(attempt):
    code = attempt.get('code')
    return f"{attempt['status']} {code}" if attempt.get('status') is not None and code else (
        str(attempt['status']) if attempt.get('status') is not None else f"error {attempt.get('error')}")


def summarize_attempts(attempts, outage):
    """Counts per phase by status and business code, per request step, and the 503 latency distribution."""
    requests = [a for a in attempts if a['step'] != 'probe']
    by_phase = {phase: Counter() for phase in PHASES}
    by_step = {}
    classes = {phase: Counter() for phase in PHASES}
    unavailable = {phase: [] for phase in PHASES}
    for attempt in requests:
        phase = phase_of(attempt['finished_s'], outage)
        outcome = Outcome(attempt.get('status'), attempt.get('code'), error=attempt.get('error'))
        by_phase[phase][label(attempt)] += 1
        by_step.setdefault(attempt['step'], {p: Counter() for p in PHASES})[phase][label(attempt)] += 1
        kind = classify(outcome)
        classes[phase][kind] += 1
        if kind == 'auth_unavailable':
            unavailable[phase].append(attempt['latency_ms'])
    return {
        'by_phase': {p: {'attempts': sum(c.values()), 'status_code': dict(sorted(c.items()))}
                     for p, c in by_phase.items()},
        'by_step': {step: {p: dict(sorted(c.items())) for p, c in phases.items()}
                    for step, phases in sorted(by_step.items())},
        'by_class': {p: dict(sorted(c.items())) for p, c in classes.items()},
        'auth_unavailable_latency': {**{p: distribution(v) for p, v in unavailable.items()},
                                     'all': distribution([x for v in unavailable.values() for x in v])},
    }


def non_policy_failures(attempts, outage=None):
    """Responses outside the policy: 5xx other than 503 AUTHENTICATION_UNAVAILABLE and requests without any answer.

    With the outage window the counts are also split by the phase in which the response (or its absence) completed.
    """
    other_5xx = connection = 0
    by_phase = {phase: {'other_5xx': 0, 'connection_errors': 0} for phase in PHASES}
    for attempt in attempts:
        if attempt['step'] == 'probe':
            continue
        kind = classify(Outcome(attempt.get('status'), attempt.get('code'), error=attempt.get('error')))
        other_5xx += kind == 'other_5xx'
        connection += kind == 'connection_error'
        if outage is not None and kind in ('other_5xx', 'connection_error'):
            by_phase[phase_of(attempt['finished_s'], outage)]['other_5xx' if kind == 'other_5xx'
                                                              else 'connection_errors'] += 1
    result = {'other_5xx': other_5xx, 'connection_errors': connection}
    if outage is not None:
        result['by_phase'] = by_phase
    return result


def throughput(attempts, flows, outage, load_end_s):
    """Responses and finished users per second in each phase; the load starts at 0 and ends with the last user."""
    edges = {'before': (0.0, outage['stop_issued_s']),
             'during': (outage['stop_issued_s'], outage['start_returned_s']),
             'after': (outage['start_returned_s'], max(load_end_s, outage['start_returned_s']))}
    result = {}
    for phase, (start, end) in edges.items():
        seconds = max(end - start, 0.0)
        mine = [a for a in attempts if a['step'] != 'probe' and phase_of(a['finished_s'], outage) == phase]
        business = sum(classify(Outcome(a.get('status'), a.get('code'), error=a.get('error'))) == 'business'
                       for a in mine)
        done = sum(phase_of(f['finished_s'], outage) == phase for f in flows)
        rate = (lambda n: round(n / seconds, 1) if seconds > 0 else None)
        result[phase] = {'seconds': round(seconds, 2), 'responses': len(mine), 'responses_per_second': rate(len(mine)),
                         'business_responses': business, 'business_responses_per_second': rate(business),
                         'users_finished': done, 'users_finished_per_second': rate(done)}
    return result


def probe_summary(attempts, outage):
    probes = [a for a in attempts if a['step'] == 'probe']
    per_phase = {}
    for phase in PHASES:
        mine = [p for p in probes if phase_of(p['finished_s'], outage) == phase]
        per_phase[phase] = {'count': len(mine),
                            'status': dict(sorted(Counter(label(p) for p in mine).items())),
                            'errors': sum(p.get('error') is not None for p in mine),
                            'latency': distribution([p['latency_ms'] for p in mine])}
    return {'interval_note': 'one probe is launched every interval whether or not the previous one has finished',
            'total': len(probes), 'by_phase': per_phase,
            'non_200_during_outage': sum(p.get('status') != 200 for p in probes
                                         if phase_of(p['finished_s'], outage) == 'during')}


def first_success_after(attempts, outage):
    """Seconds from the end of the outage to the first authenticated business response, None when there is none."""
    ends = outage['start_returned_s']
    times = [a['finished_s'] for a in attempts
             if a['step'] in ('create', 'payment') and a['finished_s'] >= ends and a.get('error') is None
             and classify(Outcome(a.get('status'), a.get('code'))) == 'business']
    return round(min(times) - ends, 3) if times else None


def trailing_up(samples, after_s):
    """Per instance: the time since which every health sample was UP, and whether the last sample is UP.

    samples is a list of {instance, t, status}. The recovery time is counted from after_s (the end of the outage).
    """
    result = {}
    for name in sorted({s['instance'] for s in samples}):
        mine = sorted((s for s in samples if s['instance'] == name), key=lambda s: s['t'])
        down_seen = any(s['status'] != 'UP' and s['t'] >= 0 for s in mine)
        last = mine[-1]['status'] == 'UP' if mine else False
        up_since = None
        if last:
            index = len(mine)
            while index > 0 and mine[index - 1]['status'] == 'UP':
                index -= 1
            up_since = mine[index]['t']
        result[name] = {'last_sample_up': last, 'down_observed': down_seen, 'samples': len(mine),
                        'up_since_s': None if up_since is None else round(up_since, 3),
                        'seconds_after_outage': None if up_since is None else round(max(0.0, up_since - after_s), 3)}
    return result


def user_summary(flows):
    results = Counter(f['result'] for f in flows)
    retries = Counter()
    for flow in flows:
        retries.update(flow['retries'])
    return {'results': dict(sorted(results.items())), 'relogins': sum(f['relogins'] for f in flows),
            'users_relogged_in': sum(f['relogins'] > 0 for f in flows),
            'retries_total': sum(retries.values()), 'retries_by_reason': dict(sorted(retries.items())),
            'users_retried': sum(sum(f['retries'].values()) > 0 for f in flows),
            'max_requests_per_user': max((f['requests'] for f in flows), default=0)}


# ---- cache against the database ----

CACHE_TIME_FIELDS = ('startsAt', 'endsAt', 'createdAt', 'updatedAt')
CACHE_FIELDS = ('saleId', 'productId', 'name', 'description', 'productNameSnapshot', 'unitPrice', 'startsAt',
                'endsAt', 'totalQuantity', 'perMemberLimit', 'soldQuantity', 'stopped', 'version', 'createdAt',
                'updatedAt')
# Values that change with orders without evicting the entry (2-3): they may be stale for up to the TTL.
CACHE_VOLATILE_FIELDS = ('soldQuantity', 'version', 'updatedAt')


def instant_micros(value):
    """Microseconds since the epoch from an ISO-8601 instant (any fraction length) or epoch seconds."""
    if isinstance(value, (int, float, Decimal)) and not isinstance(value, bool):
        return int(Decimal(str(value)) * 1_000_000)
    text = str(value).strip().replace(' ', 'T')
    if text.endswith('Z'):
        text = text[:-1]
    whole, _, fraction = text.partition('.')
    moment = datetime.strptime(whole, '%Y-%m-%dT%H:%M:%S').replace(tzinfo=timezone.utc)
    micros = int((fraction + '000000')[:6]) if fraction else 0
    return int(moment.timestamp()) * 1_000_000 + micros


def cache_mismatches(cached, row):
    """Names of the fields where the cached JSON differs from the database row (both keyed by the JSON names)."""
    differing = []
    for name in CACHE_FIELDS:
        if name not in cached or name not in row:
            differing.append(name)
            continue
        left, right = cached[name], row[name]
        if name in CACHE_TIME_FIELDS:
            same = instant_micros(left) == instant_micros(right)
        elif name == 'stopped':
            same = bool(left) == bool(right)
        else:
            same = left == right
        if not same:
            differing.append(name)
    unknown = sorted(set(cached) - set(CACHE_FIELDS))
    return differing + [f'unexpected:{name}' for name in unknown]


# ---- server records against what the client saw ----

def first_create_response(attempts_of_user):
    """CREATED or REJECTED for the first business response to the order request, None when there was none."""
    for attempt in attempts_of_user:
        if attempt['step'] != 'create' or attempt.get('error') is not None:
            continue
        if attempt.get('status') in (200, 201) and attempt.get('code') in CREATE_CREATED_CODES:
            return 'CREATED'
        if attempt.get('status') in (404, 409):
            return 'REJECTED'
    return None


def server_record_check(users, attempts, request_results, orders):
    """Compare what each user saw with the stored request results and orders.

    users maps a user index to {member_id, key}. A request that only ever got 503 or 429 must have left no stored
    request result and no order; a business response must match exactly one stored result.
    """
    by_user = {}
    for attempt in attempts:
        if attempt['step'] in ('create', 'payment'):
            by_user.setdefault(attempt['user'], []).append(attempt)
    stored = {(r['member_id'], r['key']): r for r in request_results}
    order_by_key = Counter((o['member_id'], o['key']) for o in orders)
    errors = []
    created_users = rejected_users = unanswered_users = 0
    for user, identity in sorted(users.items()):
        key = (identity['member_id'], identity['key'])
        seen = first_create_response(by_user.get(user, []))
        row = stored.get(key)
        if seen is None:
            unanswered_users += 1
            if row is not None or order_by_key.get(key):
                errors.append(f'user {user}: only unavailable or rate-limited responses but a record exists')
        elif seen == 'CREATED':
            created_users += 1
            if row is None or row['outcome'] != 'CREATED' or order_by_key.get(key) != 1:
                errors.append(f'user {user}: order created for the client but stored result or order differs')
        else:
            rejected_users += 1
            if row is None or row['outcome'] != 'REJECTED' or order_by_key.get(key):
                errors.append(f'user {user}: rejection for the client but stored result or order differs')
    known = {(u['member_id'], u['key']) for u in users.values()}
    stray_results = [k for k in stored if k not in known]
    stray_orders = [k for k in order_by_key if k not in known]
    if stray_results or stray_orders:
        errors.append(f'{len(stray_results)} stored results and {len(stray_orders)} orders belong to no user key')
    duplicates = sum(count > 1 for count in order_by_key.values())
    return {'created_users': created_users, 'rejected_users': rejected_users, 'unanswered_users': unanswered_users,
            'stored_results': len(stored), 'orders': sum(order_by_key.values()),
            'duplicate_orders_per_key': duplicates, 'errors': errors}


def lb_attempt_index(log_text):
    """{attempt seq: {upstream, status}} from the Caddy JSON access log, joined by the X-Attempt-Seq request header.

    Only these two fields are kept: the full log carries every request header, including the CSRF token.
    """
    served = {}
    for line in log_text.splitlines():
        try:
            entry = json.loads(line)
        except ValueError:
            continue
        request = entry.get('request') if isinstance(entry, dict) else None
        values = ((request or {}).get('headers') or {}).get('X-Attempt-Seq') if isinstance(request, dict) else None
        if not values:
            continue
        try:
            served[int(values[0])] = {'upstream': entry.get('upstream'), 'status': entry.get('status')}
        except ValueError:
            continue
    return served


def lb_error_entries(log_text):
    """Error and warning lines of Caddy's own log reduced to the fields that explain them.

    The full lines carry the request headers, including the CSRF token, so only the attempt number is kept.
    """
    entries = []
    for line in log_text.splitlines():
        try:
            entry = json.loads(line)
        except ValueError:
            continue
        if not isinstance(entry, dict) or entry.get('level') not in ('error', 'warn'):
            continue
        kept = {key: entry[key] for key in ('ts', 'level', 'logger', 'msg', 'error', 'duration', 'status')
                if entry.get(key) is not None}
        values = ((entry.get('request') or {}).get('headers') or {}).get('X-Attempt-Seq')
        if values:
            try:
                kept['seq'] = int(values[0])
            except ValueError:
                pass
        entries.append(kept)
    return entries


# ---- metrics ----

def counter_deltas(before, after, prefixes):
    """Non-zero differences of counter-like series ({(name, labels): value}) whose name starts with a prefix."""
    rows = []
    for (name, labels), value in sorted(after.items()):
        if not name.startswith(prefixes) or not name.endswith(('_total', '_count', '_sum')):
            continue
        difference = value - before.get((name, labels), 0)
        if difference:
            rows.append({'name': name, 'labels': dict(labels), 'delta': round(difference, 6)})
    return rows


def labelled_delta(rows, name, **labels):
    wanted = set(labels.items())
    return sum(r['delta'] for r in rows if r['name'] == name and wanted <= set(r['labels'].items()))


def dirty_outside_results(porcelain, results_prefix='benchmarks/redis-outage/'):
    """Whether the working tree has changes other than result files this runner writes itself."""
    for line in porcelain.splitlines():
        path = line[3:].strip().strip('"')
        if ' -> ' in path:
            path = path.split(' -> ')[-1]
        if path and not path.startswith(results_prefix):
            return True
    return False


# ---- pass criteria ----

def evaluate(result):
    """One dict per pass criterion: {name, passed, expected, actual}. An empty failure list means the run passed."""
    checks = []

    def check(name, expected, actual, passed=None):
        checks.append({'name': name, 'passed': expected == actual if passed is None else passed,
                       'expected': expected, 'actual': actual})

    stock, users = result['stock'], result['users']
    db, flows, attempts = result['db'], result['flows'], result['attempt_failures']
    check('confirmed', stock, db['confirmed'])
    check('remaining', 0, db['remaining'])
    check('orders_converged_to_terminal', True, db['converged'])
    check('verifier_violations', 0, result['verifier_violations'])
    check('reconciliation_violations', 0, result['sql_violations'])
    check('duplicate_orders_per_request_key', 0, result['server_records']['duplicate_orders_per_key'])
    check('rejected_requests_left_no_record_or_order', [], result['server_records']['errors'])
    check('users_confirmed', stock, flows.get('CONFIRMED', 0))
    check('users_sold_out', users - stock, flows.get('SOLD_OUT', 0))
    others = {k: v for k, v in flows.items() if k not in ('CONFIRMED', 'SOLD_OUT')}
    check('users_other_results', {}, others)
    check('completed_within_deadline', 0, flows.get('DEADLINE_EXCEEDED', 0))
    check('server_errors_other_than_503_authentication_unavailable', 0, attempts['other_5xx'])
    check('connection_errors', 0, attempts['connection_errors'])
    # A request stuck behind the outage can fail after Redis is back, so this counts the 503 of every phase.
    check('outage_was_seen_by_clients', True, result['unavailable_seen_by_clients'] > 0,
          passed=result['unavailable_seen_by_clients'] > 0)
    probes = result['probes']
    check('public_detail_probes_during_outage_not_empty', True, probes['by_phase']['during']['count'] > 0,
          passed=probes['by_phase']['during']['count'] > 0)
    check('public_detail_probes_during_outage_all_200', 0, probes['non_200_during_outage'])
    check('public_detail_probe_errors', 0, sum(p['errors'] for p in probes['by_phase'].values()))
    cache = result['cache']
    check('sale_cache_error_counter_increased', True, cache['error_delta'] > 0, passed=cache['error_delta'] > 0)
    check('cache_regenerated_after_recovery', True, cache['regenerated_after_recovery'])
    check('cache_matches_database', [], cache['final_mismatched_fields'])
    check('rate_limit_rejections_match_clients', result['client_429'], result['server_rate_limited'])
    health = result['health']
    check('management_health_up_after_recovery', {name: True for name in sorted(health)},
          {name: info['last_sample_up'] for name, info in sorted(health.items())})
    check('clock_guard', [], result['clock_errors'])
    return checks


def failures(checks):
    return [c['name'] for c in checks if not c['passed']]
