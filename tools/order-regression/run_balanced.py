#!/usr/bin/env python3
"""Run the oversubscription scenario through a Caddy load balancer in front of 1 or 2 sales instances."""
import argparse
import asyncio
import contextlib
from datetime import datetime, timedelta, timezone
import json
import secrets
import subprocess
import sys
import time
import uuid

from audit_case import audit_case
from balanced import evaluate, expected_counts, parse_access_log, plan_attempts, split, summarize_access_log
from burst import run_burst
from clock_guard import record, sample
from evidence import fixed_payment_errors, match_public_order, payment_errors
from http_client import Client
from isolated import Isolated, ROOT
from run_oversubscription import collect, expect, observe_sale_open, payment_identities, prepare_user, rows, save

USERS = 1000
STOCK = 300
RECONCILE_SQL = ROOT / 'tools/reconciliation/reconcile.sql'
INSTANCE_NAMES = ('sales1', 'sales2')


def reconciliation_rows(env):
    """Run the reconciliation SQL on the sales database; every returned row is a violation."""
    return [line for line in env.mysql('sales', 'USE limited_sale;\n' + RECONCILE_SQL.read_text()).splitlines() if line]


def converge(env, sale_id, deadline_seconds=120):
    deadline = time.monotonic() + deadline_seconds
    while True:
        snapshot = collect(env, sale_id)
        if len(snapshot['orders']) == STOCK and all(
                o['status'] in ('CONFIRMED', 'PAYMENT_FAILED', 'EXPIRED') for o in snapshot['orders']):
            return snapshot
        if time.monotonic() >= deadline:
            raise RuntimeError(f"orders did not converge: {len(snapshot['orders'])} orders after {deadline_seconds}s")
        time.sleep(1)


async def run(env, instances):
    started = time.monotonic()
    started_wall = datetime.now(timezone.utc).isoformat()
    clock_start = sample()
    phases = {}
    # Clients know only the load balancer. Each user logs in once and keeps that session for every request.
    clients = [Client(env.ports['lb']) for _ in range(USERS)]
    admin = Client(env.ports['lb'])
    await admin.login('admin@example.test', env.password)
    limit = asyncio.Semaphore(8)
    await asyncio.gather(*(prepare_user(client, i, env.password, limit) for i, client in enumerate(clients)))
    members = rows(env, 'sales', 'limited_sale',
                   "SELECT JSON_OBJECT('id',id,'email',email) FROM members WHERE role='USER';")
    by_email = {row['email']: row['id'] for row in members}
    if len(by_email) != USERS:
        raise RuntimeError('prepared user count mismatch')
    member_ids = [by_email[f'user{i}@example.test'] for i in range(USERS)]
    save(env.output / 'prepared-users.json', {'count': USERS, 'member_ids': sorted(member_ids),
                                               'prepared_at': datetime.now(timezone.utc).isoformat()})
    product = expect(*(await admin.request('POST', '/api/v1/admin/products',
                                           {'name': 'balanced', 'description': '', 'basePrice': 1000})), 201, 'product')
    start = datetime.now(timezone.utc) + timedelta(seconds=3)
    sale = expect(*(await admin.request('POST', '/api/v1/admin/sales',
                   {'productId': product['productId'], 'name': 'balanced', 'description': '', 'unitPrice': 1000,
                    'startsAt': start.isoformat(), 'endsAt': (start + timedelta(hours=1)).isoformat(),
                    'totalQuantity': STOCK, 'perMemberLimit': 1})), 201, 'sale')
    sale_id = sale['saleId']
    shuffle_seed = secrets.randbits(64)
    plans = plan_attempts(member_ids, sale_id, lambda: str(uuid.uuid4()), lambda: str(uuid.uuid4()),
                          shuffle_seed=shuffle_seed)
    results, statuses = expected_counts(plans, STOCK)
    manifest = {'sales': [{'id': sale_id, 'unit_price': 1000, 'total_quantity': STOCK, 'per_member_limit': 1}],
                'attempts': [{'attempt_id': p['id'], 'member_id': p['member_id'], 'key': p['key'],
                              'sale_id': sale_id, 'quantity': 1, 'allowed_results': ['CREATED', 'REJECTED'],
                              'rejection_reason': p['reason'], 'final_statuses': ['CONFIRMED']} for p in plans],
                'expected_results': results, 'expected_order_statuses': statuses, 'recovery_required': False,
                'shuffle_seed': shuffle_seed}
    save(env.output / 'manifest.json', manifest)
    phases['prepare_seconds'] = round(time.monotonic() - started, 2)
    await observe_sale_open(env, sale_id, env.output)

    async def send(p):
        status, body = await clients[p['client']].request('POST', '/api/v1/orders',
            {'saleId': sale_id, 'quantity': 1}, {'Idempotency-Key': p['key']},
            on_sent=lambda: p.__setitem__('write_completed', True))
        return {'http_status': status, 'body': body}
    phase = time.monotonic()
    burst = await run_burst(plans, send)
    phases['order_burst_seconds'] = round(time.monotonic() - phase, 2)
    save(env.output / 'order-attempts.json', burst)
    # A same-key replay returns the order the first attempt created, so each order is paid once.
    order_clients = {}
    for item, plan in zip(burst['attempts'], plans):
        body = (item.get('response') or {}).get('body') or {}
        if body.get('code') in ('ORDER_CREATED', 'ORDER_FOUND'):
            order_clients.setdefault(body['data']['orderId'], (plan['id'], plan['client']))
    initial_payments = payment_identities(env, sale_id)
    save(env.output / 'payment-identities-before.json', initial_payments)

    async def pay(public_id, attempt_id, client):
        try:
            status, body = await clients[client].request('POST', f'/api/v1/orders/{public_id}/payment')
            return {'attempt_id': attempt_id, 'order_public_id': public_id, 'http_status': status, 'body': body}
        except Exception as exc:
            return {'attempt_id': attempt_id, 'order_public_id': public_id, 'error': type(exc).__name__}
    phase = time.monotonic()
    payment_results = await asyncio.gather(*(pay(order, *owner) for order, owner in order_clients.items()))
    phases['payment_seconds'] = round(time.monotonic() - phase, 2)
    save(env.output / 'payment-attempts.json', payment_results)

    phase = time.monotonic()
    snapshot = converge(env, sale_id)
    await asyncio.sleep(1)
    final = collect(env, sale_id)
    phases['convergence_seconds'] = round(time.monotonic() - phase, 2)
    if snapshot != final:
        save(env.output / 'unstable-before.json', snapshot)
        save(env.output / 'unstable-after.json', final)
        raise RuntimeError('snapshot changed during quiescence check')
    stored_results = {(r['member_id'], r['key']): r for r in final['request_results']}
    orders_by_id = {o['id']: o for o in final['orders']}
    requests = []
    for item, plan in zip(burst['attempts'], plans):
        body = (item.get('response') or {}).get('body') or {}
        code = body.get('code')
        result = 'CREATED' if code in ('ORDER_CREATED', 'ORDER_FOUND') else (
            'REJECTED' if code in ('SALE_SOLD_OUT', 'PURCHASE_LIMIT_EXCEEDED') else 'ERROR')
        stored = stored_results.get((plan['member_id'], plan['key']))
        if result == 'CREATED' and not match_public_order(body, orders_by_id.get(stored['order_id']) if stored else None):
            result = 'ERROR'
        requests.append({'attempt_id': plan['id'], 'member_id': plan['member_id'], 'key': plan['key'],
                         'sale_id': sale_id, 'quantity': 1, 'sent': item['write_completed'], 'result': result,
                         'order_id': stored['order_id'] if result == 'CREATED' and stored else None,
                         'rejection_reason': code if result == 'REJECTED' else None})
    save(env.output / 'snapshot.json', {'final': True, 'manifest': manifest, 'requests': requests, **final,
                                         'recovery': None})
    counts = {'planned': len(plans), 'sent': sum(bool(r['sent']) for r in requests),
              'created': sum(r['result'] == 'CREATED' for r in requests),
              'rejected': sum(r['result'] == 'REJECTED' for r in requests),
              'errors': sum(r['result'] == 'ERROR' for r in requests)}
    save(env.output / 'counts.json', counts)

    verifier = subprocess.run([sys.executable, '-B', str(ROOT / 'tools/order-regression/verify.py'),
                               str(env.output / 'snapshot.json')], text=True, capture_output=True)
    (env.output / 'verifier.log').write_text(verifier.stdout + verifier.stderr)
    verifier_violations = max(len([x for x in verifier.stderr.splitlines() if x.strip()]), 1) if verifier.returncode else 0
    save(env.output / 'clock-guard.json', record(clock_start, sample()))
    evidence = (payment_errors(payment_results, {'PAYMENT_SUCCEEDED'}, len(order_clients))
                + fixed_payment_errors(initial_payments, final['payments']) + audit_case(env.output))
    (env.output / 'evidence-errors.log').write_text('\n'.join(evidence) + ('\n' if evidence else ''))
    violations = reconciliation_rows(env)
    (env.output / 'reconciliation-violations.tsv').write_text('\n'.join(violations) + ('\n' if violations else ''))

    await asyncio.sleep(1)
    access_log, caddy_log = env.load_balancer_logs()
    (env.output / 'lb-access.log').write_text(access_log)
    (env.output / 'lb-caddy.log').write_text(caddy_log)
    access = summarize_access_log(parse_access_log(access_log))
    sale_row = final['sales'][0]
    result = {'instances': instances, 'shuffle_seed': shuffle_seed, 'users': USERS, 'stock': STOCK, 'attempts': len(plans),
              'upstreams': env.upstreams(), 'confirmed': sum(o['status'] == 'CONFIRMED' for o in final['orders']),
              'remaining': sale_row['total_quantity'] - sale_row['reserved_quantity'] - sale_row['sold_quantity'],
              'verifier_violations': verifier_violations, 'evidence_violations': len(evidence),
              'sql_violations': len(violations), 'request_errors': counts['errors'], 'counts': counts,
              'access': access, 'order_create_split': split(access['order_creates']),
              'order_payment_split': split(access['order_payments']), 'started_at': started_wall,
              'durations': {**phases, 'total_seconds': round(time.monotonic() - started, 2)}}
    result['failures'] = evaluate(result)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--instances', type=int, choices=(1, 2), required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    env = Isolated(args.output, instances=INSTANCE_NAMES[:args.instances], load_balancer=True)
    try:
        env.start()
        result = asyncio.run(run(env, args.instances))
        save(env.output / 'summary.json', {'status': 'failed' if result['failures'] else 'passed', **result})
        print(json.dumps({key: result[key] for key in ('instances', 'confirmed', 'remaining', 'sql_violations',
                                                       'verifier_violations', 'order_create_split', 'durations',
                                                       'failures')}))
        if result['failures']:
            sys.exit(1)
    except Exception as exc:
        if hasattr(env, 'lb_container') and not (env.output / 'lb-access.log').exists():
            with contextlib.suppress(Exception):
                access_log, caddy_log = env.load_balancer_logs()
                (env.output / 'lb-access.log').write_text(access_log)
                (env.output / 'lb-caddy.log').write_text(caddy_log)
        save(env.output / 'summary.json', {'status': 'failed', 'error': f'{type(exc).__name__}: {exc}'})
        print(f'FAILED: {type(exc).__name__}: {exc}', file=sys.stderr)
        sys.exit(1)
    finally:
        env.close()
