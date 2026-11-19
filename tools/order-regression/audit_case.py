#!/usr/bin/env python3
"""Cross-check raw HTTP evidence and payment identities against a verified snapshot."""
import json
from pathlib import Path
import sys
from collections import Counter

from clock_guard import record_errors
from evidence import fixed_payment_errors, intermediate_errors, match_public_order, payment_errors, quiescence_errors, scenario_errors, terminal_poll_errors
from verify import verify


def audit_case(directory):
    data = json.loads((directory / 'snapshot.json').read_text())
    raw = json.loads((directory / 'order-attempts.json').read_text())
    batches = raw if isinstance(raw, list) else [raw]
    attempts = [a for batch in batches for a in batch['attempts']]
    errors = verify(data)
    errors += record_errors(json.loads((directory / 'clock-guard.json').read_text()))
    if any(batch['barrier_arrived'] != len(batch['attempts']) for batch in batches):
        errors.append('barrier coverage')
    ids = [a['id'] for a in attempts]
    expected_ids = [a['attempt_id'] for a in data['requests']]
    if Counter(ids) != Counter(expected_ids):
        errors.append('raw attempt coverage')
    lookup = {a['attempt_id']: a for a in data['requests']}
    orders = {o['id']: o for o in data['orders']}
    for attempt in attempts:
        row = lookup.get(attempt['id'])
        if row is None:
            continue
        response = attempt.get('response') or {}
        body = response.get('body') or {}
        code = body.get('code')
        expected_code = {'CREATED': ('ORDER_CREATED', 'ORDER_FOUND'),
                         'REJECTED': (row['rejection_reason'],), 'CONFLICT': ('IDEMPOTENCY_CONFLICT',)}
        if code not in expected_code.get(row['result'], ()):
            errors.append(f"attempt {attempt['id']}: raw outcome mismatch")
        http = response.get('http_status')
        if (code == 'ORDER_CREATED' and http != 201 or code == 'ORDER_FOUND' and http != 200
                or row['result'] in ('REJECTED', 'CONFLICT') and http != 409):
            errors.append(f"attempt {attempt['id']}: HTTP status mismatch")
        if row['result'] == 'CREATED' and not match_public_order(body, orders.get(row['order_id'])):
            errors.append(f"attempt {attempt['id']}: public order mismatch")
        if 'write_completed' not in attempt or attempt['write_completed'] != row['sent']:
            errors.append(f"attempt {attempt['id']}: send evidence mismatch")
    before = json.loads((directory / 'payment-identities-before.json').read_text())
    errors += fixed_payment_errors(before, data['payments'])
    payments = json.loads((directory / 'payment-attempts.json').read_text())
    errors += payment_errors(payments, {'PAYMENT_SUCCEEDED', 'PAYMENT_PENDING', 'PAYMENT_DECLINED'}, len(orders))
    by_public = {order['public_id']: order for order in orders.values()}
    public_ids = [p['order_public_id'] for p in payments]
    if len(set(public_ids)) != len(public_ids) or set(public_ids) != set(by_public):
        errors.append('payment request target coverage')
    for payment in payments:
        order = by_public.get(payment['order_public_id'])
        code = (payment.get('body') or {}).get('code')
        if order and ((code == 'PAYMENT_SUCCEEDED' and order['status'] != 'CONFIRMED') or
                      (code == 'PAYMENT_DECLINED' and order['status'] != 'PAYMENT_FAILED')):
            errors.append('payment response contradicts final order')
    if data['manifest']['recovery_required']:
        intermediate = json.loads((directory / 'intermediate.json').read_text())
        scenarios = json.loads((directory / 'pg-scenarios.json').read_text())
        targets = json.loads((directory / 'configuration-targets.json').read_text())
        configuration = json.loads((directory / 'configuration-attempts.json').read_text())
        observed_file = directory / 'pg-scenarios-observed.json'
        observed_pg = json.loads(observed_file.read_text())['rows'] if observed_file.exists() else data['pg_payments']
        errors += scenario_errors(scenarios, targets, observed_pg)
        configured = {r['order_public_id']: r for r in configuration}
        if len(configured) != len(configuration) or set(configured) != {r['order_public_id'] for r in scenarios}:
            errors.append('simulation configuration coverage')
        elif any(configured[r['order_public_id']].get('http_status') != 200 or
                 configured[r['order_public_id']].get('code') != 'SIMULATION_CONFIGURED' or
                 configured[r['order_public_id']].get('scenario') != r['scenario'] or
                 configured[r['order_public_id']].get('delay_seconds') != r['delay_seconds'] for r in scenarios):
            errors.append('simulation configuration outcome')
        timing = json.loads((directory / 'timing.json').read_text())
        before = json.loads((directory / 'quiescence-before.json').read_text())
        after = json.loads((directory / 'quiescence-after.json').read_text())
        events = json.loads((directory / 'pg-proxy-events.json').read_text())
        polls = json.loads((directory / 'terminal-polls.json').read_text())
        errors += terminal_poll_errors(polls, set(orders), data['recovery']['deadline_seconds'])
        first_terminal = {}
        for poll in polls:
            for target in poll['targets']:
                if target['status'] in ('CONFIRMED', 'PAYMENT_FAILED', 'EXPIRED'):
                    first_terminal.setdefault(target['id'], poll['elapsed_seconds'])
        if any(first_terminal.get(target['order_id']) != target['elapsed_seconds']
               for target in data['recovery']['targets']):
            errors.append('terminal target timing mismatch')
        if any(after['snapshot'][key] != data[key] for key in after['snapshot']):
            errors.append('final snapshot differs from quiescent capture')
        errors += quiescence_errors(before, after, events)
        errors += intermediate_errors(intermediate['rows'], scenarios)
        origin_ns = timing['recovery_origin_monotonic'] * 1_000_000_000
        if not intermediate['single_statement'] or not origin_ns <= intermediate['observed_ns'] <= origin_ns + 120_000_000_000:
            errors.append('intermediate observation time or scope')
    return errors


def main():
    try:
        errors = audit_case(Path(sys.argv[1]))
    except (OSError, ValueError, KeyError, IndexError, TypeError) as exc:
        print(f'invalid evidence: {exc}', file=sys.stderr)
        return 2
    for error in errors:
        print(error, file=sys.stderr)
    print(f'{len(errors)} HTTP evidence failure(s)')
    return bool(errors)


if __name__ == '__main__':
    sys.exit(main())
