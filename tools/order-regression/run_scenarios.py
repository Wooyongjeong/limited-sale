#!/usr/bin/env python3
"""Execute the non-oversubscription regression scenarios in isolated services."""
import argparse
import asyncio
from collections import Counter
from datetime import datetime, timedelta, timezone
import json
import sys
import time
import uuid

from audit_case import audit_case
from clock_guard import record, sample
from controlled_proxy import ControlledProxy
from burst import run_burst
from evidence import fixed_payment_errors, intermediate_errors, match_public_order, payment_errors
from http_client import Client
from isolated import Isolated
from run_oversubscription import collect, expect, observe_sale_open, payment_identities, prepare_user, rows, save
from verify import verify


def attempt(index, member, key, sale_id, group, allowed, reason=None, final=None, quantity=1):
    return {'id': str(uuid.uuid4()), 'client': index, 'member_id': member, 'key': key, 'sale_id': sale_id,
            'quantity': quantity, 'group': group, 'allowed': allowed, 'reason': reason, 'final': final}


async def suite(env, proxy, count, selected):
    stock = 30 if count == 100 else 300
    clients = [Client(env.ports['sales1' if i % 2 == 0 else 'sales2']) for i in range(count)]
    admin = Client(env.ports['sales1'])
    await admin.login('admin@example.test', env.password)
    limit = asyncio.Semaphore(8)
    await asyncio.gather(*(prepare_user(c, i, env.password, limit) for i, c in enumerate(clients)))
    members = rows(env, 'sales', 'limited_sale', "SELECT JSON_OBJECT('id',id,'email',email) FROM members WHERE role='USER';")
    by_email = {row['email']: row['id'] for row in members}
    if len(by_email) != count:
        raise RuntimeError('user preparation mismatch')
    ids = [by_email[f'user{i}@example.test'] for i in range(count)]
    save(env.output / 'prepared-users.json', {'at': datetime.now(timezone.utc).isoformat(),
         'users': [{'member_id': member, 'sales_instance': 1 if i % 2 == 0 else 2} for i, member in enumerate(ids)]})
    product = expect(*(await admin.request('POST', '/api/v1/admin/products',
                     {'name': 'scenario suite', 'description': '', 'basePrice': 1000})), 201, 'product')
    case_results = []
    for scenario in selected:
        for repetition in range(1, 4):
            # Fresh login refreshes CSRF and session TTL before each new event.
            await asyncio.gather(*(refresh(client, i, env.password, limit) for i, client in enumerate(clients)))
            case_dir = env.output / f'{scenario}-{repetition}'
            case_dir.mkdir()
            try:
                result = await case(env, proxy, admin, clients, ids, product['productId'], stock, scenario, case_dir)
                save(case_dir / 'summary.json', {'status': 'passed', **result})
                case_results.append({'scenario': scenario, 'run': repetition, 'status': 'passed', **result})
            except Exception as exc:
                error = f'{type(exc).__name__}: {exc}'
                drain_deadline = time.monotonic() + 15
                while proxy.active_count() and time.monotonic() < drain_deadline:
                    await asyncio.sleep(.1)
                save(case_dir / 'proxy-events-at-failure.json', {'active': proxy.active_count(),
                     'events': proxy.captured_events()})
                save(case_dir / 'summary.json', {'status': 'failed', 'error': error})
                case_results.append({'scenario': scenario, 'run': repetition, 'status': 'failed', 'error': error})
            save(env.output / 'suite-progress.json', case_results)
            if case_results[-1]['status'] == 'failed':
                return case_results
    return case_results


async def refresh(client, i, password, limit):
    async with limit:
        await client.login(f'user{i}@example.test', password)


async def case(env, proxy, admin, clients, ids, product_id, stock, scenario, output):
    event_offset = len(proxy.captured_events())
    case_started_wall = datetime.now(timezone.utc).isoformat()
    case_started_ns = time.monotonic_ns()
    clock_start = sample()
    count = len(clients)
    previous_pg = {p['pg_key'] for p in rows(env, 'pg', 'mock_pg',
                    "SELECT JSON_OBJECT('pg_key',BIN_TO_UUID(pg_key)) FROM mock_payments;")}
    start = datetime.now(timezone.utc) + timedelta(seconds=3)
    sale = expect(*(await admin.request('POST', '/api/v1/admin/sales',
        {'productId': product_id, 'name': scenario, 'description': '', 'unitPrice': 1000,
         'startsAt': start.isoformat(), 'endsAt': (start + timedelta(hours=1)).isoformat(),
         'totalQuantity': stock, 'perMemberLimit': 1})), 201, 'sale')
    sale_id = sale['saleId']
    plans = []
    expected = Counter()
    expected_status = Counter()
    keys = [str(uuid.uuid4()) for _ in ids]
    both = ['CREATED', 'REJECTED']
    confirmed = ['CONFIRMED']
    decline_count = stock // 10
    if scenario == 'replay_conflict':
        for group in ('first', 'replay'):
            plans.extend(attempt(i, ids[i], keys[i], sale_id, group, both, 'SALE_SOLD_OUT', confirmed) for i in range(count))
        plans.extend(attempt(i, ids[i], keys[i], sale_id, 'conflict', ['CONFLICT'], quantity=2) for i in range(count))
        expected.update(CREATED=stock*2, REJECTED=(count-stock)*2, CONFLICT=count)
        expected_status.update(CONFIRMED=stock)
    elif scenario == 'same_user':
        plans.extend(attempt(0, ids[0], str(uuid.uuid4()), sale_id, 'first', both,
                             'PURCHASE_LIMIT_EXCEEDED', confirmed) for _ in range(count))
        expected.update(CREATED=1, REJECTED=count-1)
        expected_status.update(CONFIRMED=1)
    elif scenario == 'undersubscribed':
        n = 20 if count == 100 else 200
        plans.extend(attempt(i, ids[i], keys[i], sale_id, 'first', ['CREATED'], final=confirmed) for i in range(n))
        expected.update(CREATED=n)
        expected_status.update(CONFIRMED=n)
    elif scenario == 'decline_reuse':
        for i in range(stock):
            status = ['PAYMENT_FAILED'] if i < decline_count else confirmed
            plans.append(attempt(i, ids[i], keys[i], sale_id, 'first', ['CREATED'], final=status))
            plans.append(attempt(i, ids[i], keys[i], sale_id, 'reuse', ['CREATED'], final=status))
        plans.extend(attempt(i, ids[i], keys[i], sale_id, 'reuse', both, 'SALE_SOLD_OUT', confirmed)
                     for i in range(stock, count))
        expected.update(CREATED=2*stock+decline_count, REJECTED=count-stock-decline_count)
        expected_status.update(CONFIRMED=stock, PAYMENT_FAILED=decline_count)
    else:
        for i in range(2):
            plans.append(attempt(i, ids[i], keys[i], sale_id, 'first', ['CREATED'], final=confirmed))
            plans.append(attempt(i, ids[i], keys[i], sale_id, 'burst', ['CREATED'], final=confirmed))
        plans.extend(attempt(i, ids[i], keys[i], sale_id, 'burst', both, 'SALE_SOLD_OUT', confirmed)
                     for i in range(2, count))
        expected.update(CREATED=stock+2, REJECTED=count-stock)
        expected_status.update(CONFIRMED=stock)
    manifest = {'sales': [{'id': sale_id, 'unit_price': 1000, 'total_quantity': stock, 'per_member_limit': 1}],
                'attempts': [{'attempt_id': p['id'], 'member_id': p['member_id'], 'key': p['key'],
                              'sale_id': sale_id, 'quantity': p['quantity'], 'allowed_results': p['allowed'],
                              'rejection_reason': p['reason'], 'final_statuses': p['final']} for p in plans],
                'expected_results': dict(expected), 'expected_order_statuses': dict(expected_status),
                'recovery_required': scenario == 'mixed'}
    save(output / 'manifest.json', manifest)
    await observe_sale_open(env, sale_id, output)
    raw = []
    created = {}
    async def send(p):
        status, body = await clients[p['client']].request('POST', '/api/v1/orders',
                 {'saleId': sale_id, 'quantity': p['quantity']}, {'Idempotency-Key': p['key']},
                 on_sent=lambda: p.__setitem__('write_completed', True))
        return {'http_status': status, 'body': body}
    async def group(name):
        selected = [p for p in plans if p['group'] == name]
        burst = await run_burst(selected, send)
        raw.append({'group': name, **burst})
        save(output / 'order-attempts.json', raw)
        plan_by_id = {p['id']: p for p in selected}
        for item in burst['attempts']:
            body = item.get('response', {}).get('body') or {}
            if body.get('code') in ('ORDER_CREATED', 'ORDER_FOUND'):
                created[plan_by_id[item['id']]['key']] = (plan_by_id[item['id']]['client'], body['data']['orderId'])
    async def configure(pairs):
        semaphore = asyncio.Semaphore(16)
        async def one(index, order, setting):
            async with semaphore:
                started_ns = time.monotonic_ns()
                try:
                    status, body = await admin.request('PUT', f'/api/v1/admin/orders/{order}/payment-simulation', setting)
                    return {'order_public_id': order, 'scenario': setting['scenario'],
                            'delay_seconds': setting.get('delaySeconds'), 'http_status': status,
                            'code': body.get('code') if isinstance(body, dict) else None,
                            'started_ns': started_ns, 'finished_ns': time.monotonic_ns()}
                except Exception as exc:
                    return {'order_public_id': order, 'scenario': setting['scenario'],
                            'delay_seconds': setting.get('delaySeconds'), 'error': type(exc).__name__,
                            'started_ns': started_ns, 'finished_ns': time.monotonic_ns()}
        targets = rows(env, 'sales', 'limited_sale',
            f"SELECT JSON_OBJECT('order_public_id',BIN_TO_UUID(o.public_id),'pg_key',BIN_TO_UUID(p.pg_key)) "
            f"FROM purchase_orders o JOIN payments p ON p.order_id=o.id WHERE o.sale_id={sale_id};")
        save(output / 'configuration-targets.json', targets)
        outcomes = await asyncio.gather(*(one(*p) for p in pairs))
        save(output / 'configuration-attempts.json', outcomes)
        failed = [item for item in outcomes if item.get('http_status') != 200]
        if failed:
            raise RuntimeError(f'{len(failed)} simulation configuration requests failed; see configuration-attempts.json')
    payments = []
    async def pay_selected(pairs):
        async def pay(index, order):
            sent_ns = time.monotonic_ns()
            try:
                status, body = await clients[index].request('POST', f'/api/v1/orders/{order}/payment')
                return {'client': index, 'order_public_id': order, 'http_status': status, 'body': body,
                        'started_ns': sent_ns, 'finished_ns': time.monotonic_ns()}
            except Exception as exc:
                return {'client': index, 'order_public_id': order, 'error': type(exc).__name__,
                        'started_ns': sent_ns, 'finished_ns': time.monotonic_ns()}
        payments.extend(await asyncio.gather(*(pay(index, order) for index, order in pairs)))
        save(output / 'payment-attempts.json', payments)
    await group('first')
    if scenario == 'replay_conflict':
        await group('replay')
        await group('conflict')
    scenario_evidence = []
    if scenario == 'mixed':
        await group('burst')
        pairs = list(created.values())
        if not {0, 1}.issubset({index for index, _ in pairs}):
            raise RuntimeError('preselected mixed-scenario orders were not created')
        settings = [(index, order, {'scenario': 'DELAY', 'delaySeconds': 10} if index % 2 == 0 else
                     {'scenario': 'RESPONSE_LOSS', 'delaySeconds': 5}) for index, order in pairs]
        await configure(settings)
        scenario_evidence = [{'order_public_id': order, 'scenario': setting['scenario'],
                              'delay_seconds': setting['delaySeconds']} for _, order, setting in settings]
        save(output / 'pg-scenarios.json', scenario_evidence)
    initial_payments = payment_identities(env, sale_id)
    save(output / 'payment-identities-before.json', initial_payments)
    recovery_start = time.monotonic()
    recovery_wall = datetime.now(timezone.utc).isoformat()
    if scenario == 'decline_reuse':
        pairs = [(i, created[keys[i]][1], {'scenario': 'DECLINE'}) for i in range(decline_count) if keys[i] in created]
        if len(pairs) != decline_count:
            raise RuntimeError('designated decline orders not created')
        await configure(pairs)
        await pay_selected(list(created.values()))
        await group('reuse')
        new_pairs = [pair for key, pair in created.items() if key not in set(keys[:stock])]
        before_reuse_payment = payment_identities(env, sale_id)
        previous_ids = {p['order_id'] for p in initial_payments}
        initial_payments += [p for p in before_reuse_payment if p['order_id'] not in previous_ids]
        save(output / 'payment-identities-before.json', initial_payments)
        await pay_selected(new_pairs)
    else:
        await pay_selected(list(created.values()))
    interim_errors = []
    if scenario == 'mixed':
        interim = rows(env, 'sales', 'limited_sale',
            f"SELECT JSON_OBJECT('public_id',BIN_TO_UUID(o.public_id),'order_status',o.status,"
            f"'reservation_status',r.status,'payment_status',p.status,'quantity',r.quantity) "
            f"FROM purchase_orders o JOIN sale_reservations r ON r.order_id=o.id "
            f"JOIN payments p ON p.order_id=o.id WHERE o.sale_id={sale_id};")
        save(output / 'intermediate.json', {'observed_wall': datetime.now(timezone.utc).isoformat(),
             'observed_ns': time.monotonic_ns(), 'rows': interim, 'single_statement': True})
        interim_errors = intermediate_errors(interim, scenario_evidence)
    observations = {}
    terminal_polls = []
    expected_order_count = sum(manifest['expected_order_statuses'].values())
    while True:
        target_rows = rows(env, 'sales', 'limited_sale',
            f"SELECT JSON_OBJECT('id',id,'status',status) FROM purchase_orders WHERE sale_id={sale_id};")
        elapsed = time.monotonic() - recovery_start
        for order in target_rows:
            if order['status'] in ('CONFIRMED', 'PAYMENT_FAILED', 'EXPIRED'):
                observations.setdefault(order['id'], elapsed)
        active = proxy.active_count()
        terminal_polls.append({'elapsed_seconds': elapsed, 'observed_wall': datetime.now(timezone.utc).isoformat(),
                               'active_pg_requests': active, 'targets': target_rows})
        if (len(target_rows) == expected_order_count and len(observations) == expected_order_count and active == 0):
            break
        if elapsed >= 120:
            save(output / 'terminal-polls.json', terminal_polls)
            raise RuntimeError('all expected targets and idle PG were not observed by the deadline')
        await asyncio.sleep(1)
    save(output / 'terminal-polls.json', terminal_polls)
    before_started_ns = time.monotonic_ns()
    snap = collect(env, sale_id)
    snap['pg_payments'] = [p for p in snap['pg_payments'] if p['pg_key'] not in previous_pg]
    save(output / 'quiescence-before.json', {'observed_wall': datetime.now(timezone.utc).isoformat(),
         'started_ns': before_started_ns, 'observed_ns': time.monotonic_ns(),
         'active_pg_requests': proxy.active_count(), 'snapshot': snap})
    await asyncio.sleep(1)
    after_started_ns = time.monotonic_ns()
    final = collect(env, sale_id)
    final['pg_payments'] = [p for p in final['pg_payments'] if p['pg_key'] not in previous_pg]
    save(output / 'quiescence-after.json', {'observed_wall': datetime.now(timezone.utc).isoformat(),
         'started_ns': after_started_ns, 'observed_ns': time.monotonic_ns(),
         'active_pg_requests': proxy.active_count(), 'snapshot': final})
    proxy_events = proxy.captured_events()[event_offset:]
    save(output / 'pg-proxy-events.json', proxy_events)
    after_observed_ns = time.monotonic_ns()
    overlapping = [event for event in proxy_events if event['started_ns'] < after_observed_ns and
                   event['finished_ns'] > before_started_ns]
    if final != snap or proxy.active_count() != 0 or overlapping:
        raise RuntimeError('snapshot changed or PG requests remained during quiescence check')
    stored = {(r['member_id'], r['key']): r for r in final['request_results']}
    orders = {o['id']: o for o in final['orders']}
    plans_by_id = {p['id']: p for p in plans}
    requests = []
    for batch in raw:
        for item in batch['attempts']:
            p = plans_by_id[item['id']]
            body = item.get('response', {}).get('body') or {}
            code = body.get('code')
            row = stored.get((p['member_id'], p['key']))
            result = ('CREATED' if code in ('ORDER_CREATED', 'ORDER_FOUND') else
                      'REJECTED' if code in ('SALE_SOLD_OUT', 'PURCHASE_LIMIT_EXCEEDED') else
                      'CONFLICT' if code == 'IDEMPOTENCY_CONFLICT' else 'ERROR')
            if result == 'CREATED' and not match_public_order(body, orders.get(row['order_id']) if row else None):
                result = 'ERROR'
            requests.append({'attempt_id': p['id'], 'member_id': p['member_id'], 'key': p['key'],
                             'sale_id': sale_id, 'quantity': p['quantity'], 'sent': item['write_completed'],
                             'result': result, 'order_id': row['order_id'] if result == 'CREATED' and row else None,
                             'rejection_reason': code if result == 'REJECTED' else None})
    recovery = None
    if scenario == 'mixed':
        recovery = {'deadline_seconds': 120, 'end_elapsed_seconds': time.monotonic()-recovery_start,
                    'targets': [{'order_id': o['id'], 'elapsed_seconds': observations.get(o['id'])} for o in final['orders']]}
    document = {'final': True, 'manifest': manifest, 'requests': requests, **final, 'recovery': recovery}
    save(output / 'snapshot.json', document)
    counts = {'planned': len(plans), 'sent': sum(bool(r['sent']) for r in requests),
              'results': dict(Counter(r['result'] for r in requests)),
              'rejections': dict(Counter(r['rejection_reason'] for r in requests if r['result'] == 'REJECTED')),
              'errors': sum(r['result'] == 'ERROR' for r in requests),
              'missing': len({p['id'] for p in plans} - {r['attempt_id'] for r in requests})}
    save(output / 'counts.json', counts)
    save(output / 'timing.json', {'case_started_wall': case_started_wall, 'case_started_ns': case_started_ns,
                                  'case_finished_wall': datetime.now(timezone.utc).isoformat(),
                                  'recovery_origin_monotonic': recovery_start, 'recovery_origin_wall': recovery_wall,
                                  'end_monotonic': time.monotonic(), 'terminal_elapsed_seconds': observations})
    save(output / 'clock-guard.json', record(clock_start, sample()))  # audit_case below fails the case on drift
    allowed_payment_codes = {'PAYMENT_SUCCEEDED', 'PAYMENT_PENDING'} if scenario == 'mixed' else {'PAYMENT_SUCCEEDED', 'PAYMENT_DECLINED'} if scenario == 'decline_reuse' else {'PAYMENT_SUCCEEDED'}
    errors = (verify(document) + payment_errors(payments, allowed_payment_codes, len(created))
              + fixed_payment_errors(initial_payments, final['payments']) + audit_case(output) + interim_errors)
    (output / 'verifier.log').write_text('\n'.join(errors) if errors else '0 invariant failure(s)\n')
    if errors:
        raise RuntimeError(f'{len(errors)} verifier errors; see verifier.log')
    if scenario == 'decline_reuse':
        replacement_orders = [o for o in final['orders'] if o['member_id'] in set(ids[stock:])]
        if len(replacement_orders) != decline_count or any(o['status'] != 'CONFIRMED' for o in replacement_orders):
            raise RuntimeError('released stock was not purchased by different users')
    return {**counts, 'orders': dict(Counter(o['status'] for o in final['orders'])),
            'barriers': {batch['group']: batch['barrier_arrived'] for batch in raw}}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--users', type=int, choices=(100, 1000), required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--scenarios', nargs='+', choices=('replay_conflict', 'same_user', 'undersubscribed', 'decline_reuse', 'mixed'),
                        default=('replay_conflict', 'same_user', 'undersubscribed', 'decline_reuse', 'mixed'))
    args = parser.parse_args()
    env = Isolated(args.output)
    proxy = ControlledProxy(0)
    proxy.start()
    env.sales_proxy = f'http://127.0.0.1:{proxy.port}'
    try:
        env.start()
        proxy.target_port = env.ports['pg']
        result = asyncio.run(suite(env, proxy, args.users, args.scenarios))
        failed = sum(case['status'] != 'passed' for case in result)
        save(env.output / 'summary.json', {'cases': result, 'failed': failed})
        print(json.dumps({'cases': len(result), 'failed': failed}))
        if failed:
            sys.exit(1)
    except Exception as exc:
        save(env.output / 'summary.json', {'status': 'failed', 'error': f'{type(exc).__name__}: {exc}'})
        print(f'FAILED: {type(exc).__name__}: {exc}', file=sys.stderr)
        sys.exit(1)
    finally:
        proxy.stop()
        env.close()
