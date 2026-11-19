#!/usr/bin/env python3
"""Observe an actual eleven-minute isolated Mock PG outage."""
import argparse
import asyncio
from datetime import datetime, timedelta, timezone
import json
import signal
import sys
import time
import uuid

from audit_outage import audit, audit_logs
from clock_guard import clock_errors, record, sample
from http_client import Client
from isolated import Isolated
from run_oversubscription import collect, expect, observe_sale_open, prepare_user, rows, save


def now():
    return datetime.now(timezone.utc).isoformat()


async def experiment(env):
    admin = Client(env.ports['sales1'])
    user = Client(env.ports['sales2'])
    await admin.login('admin@example.test', env.password)
    await prepare_user(user, 0, env.password, asyncio.Semaphore(1))
    member = rows(env, 'sales', 'limited_sale', "SELECT JSON_OBJECT('id',id) FROM members WHERE email='user0@example.test';")[0]['id']
    product = expect(*(await admin.request('POST', '/api/v1/admin/products',
                     {'name': 'outage', 'description': '', 'basePrice': 1000})), 201, 'product')
    start = datetime.now(timezone.utc) + timedelta(seconds=3)
    sale = expect(*(await admin.request('POST', '/api/v1/admin/sales',
                  {'productId': product['productId'], 'name': 'outage', 'description': '', 'unitPrice': 1000,
                   'startsAt': start.isoformat(), 'endsAt': (start + timedelta(hours=1)).isoformat(),
                   'totalQuantity': 1, 'perMemberLimit': 1})), 201, 'sale')
    sale_id = sale['saleId']
    key = str(uuid.uuid4())
    manifest = {'sales': [{'id': sale_id, 'unit_price': 1000, 'total_quantity': 1, 'per_member_limit': 1}],
                'attempts': [{'attempt_id': 'order', 'member_id': member, 'key': key, 'sale_id': sale_id,
                              'quantity': 1, 'allowed_results': ['CREATED'], 'rejection_reason': None,
                              'final_statuses': ['CONFIRMED']}], 'expected_results': {'CREATED': 1},
                'expected_order_statuses': {'CONFIRMED': 1}, 'recovery_required': True}
    save(env.output / 'manifest.json', manifest)
    await observe_sale_open(env, sale_id, env.output)
    status, response = await user.request('POST', '/api/v1/orders', {'saleId': sale_id, 'quantity': 1},
                                          {'Idempotency-Key': key})
    order = expect(status, response, 201, 'order')
    public_id = order['orderId']
    status, body = await admin.request('PUT', f'/api/v1/admin/orders/{public_id}/payment-simulation',
                                       {'scenario': 'RESPONSE_LOSS', 'delaySeconds': 5})
    expect(status, body, 200, 'configure PG')
    status, payment_response = await user.request('POST', f'/api/v1/orders/{public_id}/payment')
    if status != 202 or payment_response['code'] != 'PAYMENT_PENDING':
        raise RuntimeError(f'expected pending payment, got {status}')
    pg_process = env.processes[0]
    pg_process.send_signal(signal.SIGTERM)
    pg_process.wait(timeout=20)
    begin = time.monotonic()
    clock_start = sample()
    outage_started = now()
    initial = collect(env, sale_id)
    if len(initial['orders']) != 1 or initial['orders'][0]['status'] != 'PAYMENT_PENDING' or initial['reservations'][0]['status'] != 'RESERVED' or initial['pg_payments'][0]['status'] != 'SUCCEEDED':
        raise RuntimeError('required pending order, reserved stock, and persisted PG success not present')
    order_id = initial['orders'][0]['id']
    save(env.output / 'before-outage.json', {'wall_time': outage_started, 'snapshot': initial,
                                              'payment_response': payment_response, 'pg_pid': pg_process.pid,
                                              'pg_exit': pg_process.returncode})
    samples = []
    while time.monotonic() - begin < 660:
        observation = rows(env, 'sales', 'limited_sale',
            f"SELECT JSON_OBJECT('status',o.status,'requires_review',o.requires_review,'expires_at',o.expires_at,"
            f"'next_recovery_at',o.next_recovery_at,'next_review_warning_at',o.next_review_warning_at,"
            f"'lease_until',o.lease_until,'payment_status',p.status,'last_checked_at',p.last_checked_at,"
            f"'reserved_quantity',s.reserved_quantity,'sold_quantity',s.sold_quantity,'reservation',r.status) "
            f"FROM purchase_orders o JOIN payments p ON p.order_id=o.id JOIN sales s ON s.id=o.sale_id "
            f"JOIN sale_reservations r ON r.order_id=o.id WHERE o.id={order_id};")
        samples.append({'elapsed_seconds': time.monotonic() - begin, 'wall_time': now(), 'row': observation[0]})
        save(env.output / 'outage-samples.json', samples)
        await asyncio.sleep(min(10, max(0, 660 - (time.monotonic() - begin))))
    downtime = time.monotonic() - begin
    recovery_start = time.monotonic()
    restart_wall = now()
    restarted = env.start_pg('pg-restarted')
    env.health(env.ports['pg'], restarted)
    terminal_elapsed = None
    while time.monotonic() - recovery_start <= 120:
        snapshot = collect(env, sale_id)
        if snapshot['orders'][0]['status'] in ('CONFIRMED', 'PAYMENT_FAILED', 'EXPIRED'):
            terminal_elapsed = time.monotonic() - recovery_start
            break
        await asyncio.sleep(1)
    await asyncio.sleep(1)
    final = collect(env, sale_id)
    clock_end = sample()
    save(env.output / 'clock-guard.json', record(clock_start, clock_end))
    if final != snapshot:
        save(env.output / 'unstable-before.json', snapshot)
        save(env.output / 'unstable-after.json', final)
        raise RuntimeError('snapshot changed during quiescence check')
    req = {'attempt_id': 'order', 'member_id': member, 'key': key, 'sale_id': sale_id, 'quantity': 1,
           'sent': True, 'result': 'CREATED', 'order_id': order_id, 'rejection_reason': None}
    result = {'final': True, 'manifest': manifest, 'requests': [req], **final,
              'recovery': {'deadline_seconds': 120, 'end_elapsed_seconds': time.monotonic() - recovery_start,
                           'targets': [{'order_id': order_id, 'elapsed_seconds': terminal_elapsed}]}}
    save(env.output / 'snapshot.json', result)
    warnings = []
    for name in ('sales1.log', 'sales2.log'):
        for line in (env.output / name).read_text().splitlines():
            if public_id in line and 'requires payment review' in line:
                warnings.append(line)
    warnings.sort()
    save(env.output / 'outage-summary.json', {'started_at': outage_started, 'restart_started_at': restart_wall,
         'downtime_seconds': downtime, 'restart_pid': restarted.pid, 'terminal_elapsed_seconds': terminal_elapsed,
         'sample_count': len(samples), 'warnings': warnings})
    errors = clock_errors(clock_start, clock_end)
    if errors:
        raise RuntimeError('; '.join(errors))
    if downtime < 660 or terminal_elapsed is None or terminal_elapsed > 120:
        raise RuntimeError('outage duration or convergence deadline failed')
    if not all(s['row']['reservation'] == 'RESERVED' and s['row']['reserved_quantity'] == 1 for s in samples):
        raise RuntimeError('reservation was not retained throughout outage')
    errors = audit(samples)
    errors.extend(audit_logs(warnings, samples[0]['row']['expires_at']))
    if errors:
        raise RuntimeError('; '.join(errors))
    from verify import verify
    errors = verify(result)
    if errors:
        raise RuntimeError('snapshot invariants: ' + '; '.join(errors))
    return {'downtime_seconds': downtime, 'terminal_elapsed_seconds': terminal_elapsed, 'warnings': len(warnings)}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    env = Isolated(args.output)
    try:
        env.start()
        result = asyncio.run(experiment(env))
        save(env.output / 'summary.json', {'status': 'passed', **result})
        print(json.dumps(result))
    except Exception as exc:
        save(env.output / 'summary.json', {'status': 'failed', 'error': f'{type(exc).__name__}: {exc}'})
        print(f'FAILED: {type(exc).__name__}: {exc}', file=sys.stderr)
        sys.exit(1)
    finally:
        env.close()
