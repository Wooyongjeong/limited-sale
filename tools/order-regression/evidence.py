"""Checks that connect HTTP evidence to stored order identities."""


def match_public_order(response, order):
    return order is not None and response.get('data', {}).get('orderId') == order.get('public_id')


def payment_errors(results, allowed_codes, expected_count):
    errors = []
    if len(results) != expected_count:
        errors.append(f'payment attempt count {len(results)} expected {expected_count}')
    statuses = {'PAYMENT_SUCCEEDED': 200, 'PAYMENT_PENDING': 202, 'PAYMENT_DECLINED': 409}
    for result in results:
        code = (result.get('body') or {}).get('code')
        if 'error' in result or code not in allowed_codes or result.get('http_status') != statuses.get(code):
            errors.append(f"payment attempt failed for {result.get('order_public_id', 'unknown')}")
    return errors


def fixed_payment_errors(before, after):
    def identities(rows):
        return {(r['order_id'], r['pg_key'], r['amount']) for r in rows}
    if len(identities(before)) != len(before) or len(identities(after)) != len(after):
        return ['duplicate payment identities']
    return [] if identities(before) == identities(after) else ['payment key or amount changed or missing']


def intermediate_errors(rows, scenarios):
    errors = []
    scenario_by_id = {s['order_public_id']: s['scenario'] for s in scenarios}
    if len({r['public_id'] for r in rows}) != len(rows) or {r['public_id'] for r in rows} != set(scenario_by_id):
        errors.append('intermediate target coverage')
    pending_scenarios = set()
    for row in rows:
        state = row['order_status']
        if state not in ('PAYMENT_PENDING', 'AWAITING_PAYMENT', 'CONFIRMED', 'PAYMENT_FAILED', 'EXPIRED'):
            errors.append('unknown intermediate order status')
        reservation = row['reservation_status']
        if state in ('PAYMENT_PENDING', 'AWAITING_PAYMENT'):
            if reservation != 'RESERVED':
                errors.append('pending order has no reservation')
            pending_scenarios.add(scenario_by_id.get(row['public_id']))
        elif state == 'CONFIRMED' and reservation != 'CONFIRMED':
            errors.append('confirmed order reservation mismatch')
        elif state in ('PAYMENT_FAILED', 'EXPIRED') and reservation != 'RELEASED':
            errors.append('terminal failure reservation mismatch')
    if not {'DELAY', 'RESPONSE_LOSS'} <= pending_scenarios:
        errors.append('pending reservation not observed for both scenarios')
    return errors


def terminal_poll_errors(polls, expected_ids, deadline):
    if not polls:
        return ['terminal observations missing']
    last = polls[-1]
    targets = last['targets']
    if (len(targets) != len(expected_ids) or {r['id'] for r in targets} != expected_ids or
            any(r['status'] not in ('CONFIRMED', 'PAYMENT_FAILED', 'EXPIRED') for r in targets) or
            last['active_pg_requests'] != 0 or last['elapsed_seconds'] > deadline):
        return ['terminal targets, PG idleness, or deadline not established']
    return []


def quiescence_errors(before, after, events):
    if (not before['started_ns'] <= before['observed_ns'] <= after['started_ns'] <= after['observed_ns'] or
            before['snapshot'] != after['snapshot'] or before['active_pg_requests'] != 0 or
            after['active_pg_requests'] != 0 or any(event['started_ns'] < after['observed_ns'] and
            event['finished_ns'] > before['started_ns'] for event in events)):
        return ['snapshot was not quiescent']
    return []


def scenario_errors(plan, targets, observed):
    planned = {row['order_public_id']: row for row in plan}
    mapped = {row['order_public_id']: row['pg_key'] for row in targets}
    actual = {row['pg_key']: row for row in observed}
    if (len(planned) != len(plan) or len(mapped) != len(targets) or len(actual) != len(observed) or
            set(planned) != set(mapped) or set(mapped.values()) != set(actual)):
        return ['PG scenario target coverage']
    for order_id, expected in planned.items():
        remote = actual[mapped[order_id]]
        if remote['scenario'] != expected['scenario'] or remote['delay_seconds'] != expected['delay_seconds']:
            return ['PG scenario or delay mismatch']
    return []
