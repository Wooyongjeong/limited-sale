#!/usr/bin/env python3
"""Run one isolated oversubscription experiment; other scenarios are not implemented."""
import argparse
import asyncio
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import subprocess
import sys
import time
import uuid

from audit_case import audit_case
from burst import run_burst
from clock_guard import record, sample
from http_client import Client
from evidence import fixed_payment_errors, match_public_order, payment_errors
from isolated import Isolated, ROOT
from sale_gate import wait_open
from snapshot import collect, rows


def save(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')


def expect(status, body, expected, step):
    if status != expected:
        raise RuntimeError(f'{step}: HTTP {status}, code {body.get("code") if isinstance(body, dict) else None}')
    return body['data']


async def prepare_user(client, index, password, limit):
    async with limit:
        email = f'user{index}@example.test'
        await client.get_csrf()
        status, body = await client.request('POST', '/api/v1/members',
                                            {'email': email, 'password': password, 'nickname': f'user{index}'})
        expect(status, body, 201, 'registration')
        await client.login(email, password)
        status, body = await client.request('GET', '/api/v1/members/me')
        me = expect(status, body, 200, 'session verification')
        if me['email'] != email:
            raise RuntimeError('session belongs to another user')


async def observe_sale_open(env, sale_id, output):
    samples = []
    def read():
        before = datetime.now(timezone.utc).isoformat()
        found = rows(env, 'sales', 'limited_sale',
            f"SELECT JSON_OBJECT('open', UTC_TIMESTAMP(6)>=starts_at AND UTC_TIMESTAMP(6)<ends_at,"
            f"'db_now',UTC_TIMESTAMP(6),'starts_at',starts_at,'ends_at',ends_at) FROM sales WHERE id={sale_id};")
        sample = {**found[0], 'host_before': before, 'host_after': datetime.now(timezone.utc).isoformat(),
                  'monotonic_ns': time.monotonic_ns()}
        samples.append(sample)
        return sample
    try:
        await wait_open(read)
    finally:
        save(output / 'sale-clock-observations.json', samples)


def payment_identities(env, sale_id):
    return rows(env, 'sales', 'limited_sale',
        f"SELECT JSON_OBJECT('order_id',p.order_id,'pg_key',BIN_TO_UUID(p.pg_key),'amount',p.amount) "
        f"FROM payments p JOIN purchase_orders o ON o.id=p.order_id WHERE o.sale_id={sale_id};")


async def run(env, count, stock):
    case_started_wall = datetime.now(timezone.utc).isoformat()
    case_started_ns = time.monotonic_ns()
    clock_start = sample()
    clients = [Client(env.ports['sales1' if i % 2 == 0 else 'sales2']) for i in range(count)]
    admin = Client(env.ports['sales1'])
    await admin.login('admin@example.test', env.password)
    await asyncio.gather(*(prepare_user(client, i, env.password, asyncio_limit) for i, client in enumerate(clients)))
    members = rows(env, 'sales', 'limited_sale', "SELECT JSON_OBJECT('id',id,'email',email) FROM members WHERE role='USER';")
    by_email = {row['email']: row['id'] for row in members}
    if len(by_email) != count:
        raise RuntimeError('prepared user count mismatch')
    save(env.output / 'prepared-users.json', {'count': count, 'member_ids': sorted(by_email.values()),
                                               'verified_sessions': [{'member_id': by_email[f'user{i}@example.test'], 'sales_instance': 1 if i % 2 == 0 else 2} for i in range(count)], 'prepared_at': datetime.now(timezone.utc).isoformat()})
    product = expect(*(await admin.request('POST', '/api/v1/admin/products',
                                           {'name': 'regression', 'description': '', 'basePrice': 1000})), 201, 'product')
    start = datetime.now(timezone.utc) + timedelta(seconds=3)
    sale = expect(*(await admin.request('POST', '/api/v1/admin/sales',
                   {'productId': product['productId'], 'name': 'regression', 'description': '', 'unitPrice': 1000,
                    'startsAt': start.isoformat(), 'endsAt': (start + timedelta(hours=1)).isoformat(),
                    'totalQuantity': stock, 'perMemberLimit': 1})), 201, 'sale')
    sale_id = sale['saleId']
    attempts = [{'id': str(uuid.uuid4()), 'member_id': by_email[f'user{i}@example.test'], 'key': str(uuid.uuid4()),
                 'sale_id': sale_id, 'quantity': 1, 'client': i} for i in range(count)]
    manifest = {'sales': [{'id': sale_id, 'unit_price': 1000, 'total_quantity': stock, 'per_member_limit': 1}],
                'attempts': [{'attempt_id': a['id'], 'member_id': a['member_id'], 'key': a['key'], 'sale_id': sale_id,
                              'quantity': 1, 'allowed_results': ['CREATED', 'REJECTED'], 'rejection_reason': 'SALE_SOLD_OUT',
                              'final_statuses': ['CONFIRMED']} for a in attempts],
                'expected_results': {'CREATED': stock, 'REJECTED': count-stock},
                'expected_order_statuses': {'CONFIRMED': stock}, 'recovery_required': False}
    save(env.output / 'manifest.json', manifest)
    await observe_sale_open(env, sale_id, env.output)
    async def send(a):
        status, body = await clients[a['client']].request('POST', '/api/v1/orders',
            {'saleId': sale_id, 'quantity': 1}, {'Idempotency-Key': a['key']},
            on_sent=lambda: a.__setitem__('write_completed', True))
        return {'http_status': status, 'body': body}
    burst = await run_burst(attempts, send)
    save(env.output / 'order-attempts.json', burst)
    order_ids = []
    for item in burst['attempts']:
        body = item.get('response', {}).get('body') or {}
        if body.get('code') in ('ORDER_CREATED', 'ORDER_FOUND'):
            order_ids.append((item['id'], body['data']['orderId']))
    lookup = {a['id']: a for a in attempts}
    initial_payments = payment_identities(env, sale_id)
    save(env.output / 'payment-identities-before.json', initial_payments)
    async def pay(pair):
        attempt_id, order_id = pair
        a = lookup[attempt_id]
        try:
            status, body = await clients[a['client']].request('POST', f'/api/v1/orders/{order_id}/payment')
            return {'attempt_id': attempt_id, 'order_public_id': order_id, 'http_status': status, 'body': body}
        except Exception as exc:
            return {'attempt_id': attempt_id, 'order_public_id': order_id, 'error': type(exc).__name__}
    payment_results = await asyncio.gather(*(pay(pair) for pair in order_ids))
    save(env.output / 'payment-attempts.json', payment_results)
    deadline = time.monotonic() + 120
    while True:
        snapshot = collect(env, sale_id)
        if len(snapshot['orders']) == stock and all(o['status'] in ('CONFIRMED', 'PAYMENT_FAILED', 'EXPIRED') for o in snapshot['orders']):
            break
        if time.monotonic() >= deadline:
            break
        await asyncio.sleep(1)
    await asyncio.sleep(1)
    final = collect(env, sale_id)
    if snapshot != final:
        save(env.output / 'unstable-before.json', snapshot)
        save(env.output / 'unstable-after.json', final)
        raise RuntimeError('snapshot changed during quiescence check')
    result_ids = {(r['member_id'], r['key']): r for r in final['request_results']}
    orders_by_id = {o['id']: o for o in final['orders']}
    requests = []
    for item in burst['attempts']:
        a = lookup[item['id']]
        body = item.get('response', {}).get('body') or {}
        code = body.get('code')
        result = 'CREATED' if code in ('ORDER_CREATED', 'ORDER_FOUND') else ('REJECTED' if code in ('SALE_SOLD_OUT', 'PURCHASE_LIMIT_EXCEEDED') else 'ERROR')
        stored = result_ids.get((a['member_id'], a['key']))
        if result == 'CREATED' and not match_public_order(body, orders_by_id.get(stored['order_id']) if stored else None):
            result = 'ERROR'
        requests.append({'attempt_id': a['id'], 'member_id': a['member_id'], 'key': a['key'], 'sale_id': sale_id,
                         'quantity': 1, 'sent': item['write_completed'], 'result': result,
                         'order_id': stored['order_id'] if result == 'CREATED' and stored else None,
                         'rejection_reason': code if result == 'REJECTED' else None})
    document = {'final': True, 'manifest': manifest, 'requests': requests, **final, 'recovery': None}
    save(env.output / 'snapshot.json', document)
    counts = {'planned': len(attempts), 'sent': sum(bool(r['sent']) for r in requests),
              'created': sum(r['result'] == 'CREATED' for r in requests),
              'rejected': sum(r['result'] == 'REJECTED' for r in requests),
              'errors': sum(r['result'] == 'ERROR' for r in requests),
              'missing': len({a['id'] for a in attempts} - {r['attempt_id'] for r in requests})}
    save(env.output / 'counts.json', counts)
    result = subprocess.run([sys.executable, '-B', str(ROOT / 'tools/order-regression/verify.py'), str(env.output / 'snapshot.json')],
                            text=True, capture_output=True)
    (env.output / 'verifier.log').write_text(result.stdout + result.stderr)
    save(env.output / 'clock-guard.json', record(clock_start, sample()))  # audit_case below fails the run on drift
    errors = (payment_errors(payment_results, {'PAYMENT_SUCCEEDED'}, len(order_ids))
              + fixed_payment_errors(initial_payments, final['payments']) + audit_case(env.output))
    if result.returncode or errors:
        with (env.output / 'verifier.log').open('a') as log:
            log.write('\n'.join(errors) + '\n')
        raise RuntimeError(f'verifier failed ({result.returncode}); see verifier.log')
    save(env.output / 'timing.json', {'case_started_wall': case_started_wall, 'case_started_ns': case_started_ns,
         'case_finished_wall': datetime.now(timezone.utc).isoformat(), 'case_finished_ns': time.monotonic_ns()})
    return {**counts, 'confirmed': sum(o['status'] == 'CONFIRMED' for o in final['orders'])}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--users', type=int, choices=(100, 1000), required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    asyncio_limit = asyncio.Semaphore(8)
    env = Isolated(args.output)
    try:
        env.start()
        result = asyncio.run(run(env, args.users, 30 if args.users == 100 else 300))
        save(env.output / 'summary.json', {'result': result, 'status': 'passed'})
        print(json.dumps(result))
    except Exception as exc:
        save(env.output / 'summary.json', {'status': 'failed', 'error': f'{type(exc).__name__}: {exc}'})
        print(f'FAILED: {type(exc).__name__}: {exc}', file=sys.stderr)
        sys.exit(1)
    finally:
        env.close()
