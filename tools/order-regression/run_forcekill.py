#!/usr/bin/env python3
"""Observe three durable payment boundaries and SIGKILL the owning sales JVM."""
import argparse
import asyncio
from datetime import datetime, timedelta, timezone
import json
import signal
import subprocess
import sys
import time
import uuid

from audit_forcekill import audit_boundary
from clock_guard import clock_errors, record, sample
from controlled_proxy import ControlledProxy
from http_client import Client
from isolated import Isolated
from run_oversubscription import collect, expect, observe_sale_open, prepare_user, rows, save
from verify import verify


def wall():
    return datetime.now(timezone.utc).isoformat()


async def lock_sale(env, sale_id):
    cid = env.containers[0]
    process = subprocess.Popen(['docker', 'exec', '-i', cid, 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --raw --skip-column-names --unbuffered limited_sale'],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    process.stdin.write(f'SELECT CONNECTION_ID(); START TRANSACTION; SELECT id FROM sales WHERE id={sale_id} FOR UPDATE; SELECT SLEEP(120);\n')
    process.stdin.flush()
    connection = int(await asyncio.wait_for(asyncio.to_thread(process.stdout.readline), 10))
    locked_id = int(await asyncio.wait_for(asyncio.to_thread(process.stdout.readline), 10))
    if locked_id != sale_id:
        raise RuntimeError('sale row lock mismatch')
    return process, connection


def unlock_sale(env, process, connection):
    try:
        env.mysql('sales', f'KILL CONNECTION {connection};')
    finally:
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.terminate()
            process.wait(timeout=10)


async def experiment(env, proxy):
    admin = Client(env.ports['sales2'])
    clients = [Client(env.ports['sales1' if i % 2 == 0 else 'sales2']) for i in range(100)]
    user = clients[0]
    await admin.login('admin@example.test', env.password)
    limit = asyncio.Semaphore(8)
    await asyncio.gather(*(prepare_user(client, i, env.password, limit) for i, client in enumerate(clients)))
    prepared = rows(env, 'sales', 'limited_sale', "SELECT JSON_OBJECT('id',id,'email',email) FROM members WHERE role='USER';")
    if len(prepared) != 100:
        raise RuntimeError('prepared member count mismatch')
    save(env.output / 'prepared-users.json', {'profile_users': 100, 'stock': 30,
         'members': [{'id': row['id'], 'sales_instance': 1 if int(row['email'][4:].split('@')[0]) % 2 == 0 else 2} for row in prepared]})
    member = rows(env, 'sales', 'limited_sale', "SELECT JSON_OBJECT('id',id) FROM members WHERE email='user0@example.test';")[0]['id']
    product = expect(*(await admin.request('POST', '/api/v1/admin/products',
                     {'name': 'forcekill', 'description': '', 'basePrice': 1000})), 201, 'product')
    sales_process = env.processes[1]
    outcomes = []
    for boundary in ('lease', 'pg_success', 'payment_persisted'):
        for iteration in range(1, 4):
            case_dir = env.output / f'{boundary}-{iteration}'
            case_dir.mkdir()
            await user.login('user0@example.test', env.password)
            start = datetime.now(timezone.utc) + timedelta(seconds=3)
            sale = expect(*(await admin.request('POST', '/api/v1/admin/sales',
                {'productId': product['productId'], 'name': boundary, 'description': '', 'unitPrice': 1000,
                 'startsAt': start.isoformat(), 'endsAt': (start + timedelta(hours=1)).isoformat(),
                 'totalQuantity': 30, 'perMemberLimit': 1})), 201, 'sale')
            sale_id = sale['saleId']
            key = str(uuid.uuid4())
            manifest = {'sales': [{'id': sale_id, 'unit_price': 1000, 'total_quantity': 30, 'per_member_limit': 1}],
                        'attempts': [{'attempt_id': 'order', 'member_id': member, 'key': key, 'sale_id': sale_id,
                                      'quantity': 1, 'allowed_results': ['CREATED'], 'rejection_reason': None,
                                      'final_statuses': ['CONFIRMED']}], 'expected_results': {'CREATED': 1},
                        'expected_order_statuses': {'CONFIRMED': 1}, 'recovery_required': True}
            save(case_dir / 'manifest.json', manifest)
            await observe_sale_open(env, sale_id, case_dir)
            status, body = await user.request('POST', '/api/v1/orders', {'saleId': sale_id, 'quantity': 1},
                                               {'Idempotency-Key': key})
            public_id = expect(status, body, 201, 'order')['orderId']
            order_row = rows(env, 'sales', 'limited_sale', f"SELECT JSON_OBJECT('id',id) FROM purchase_orders WHERE public_id=UUID_TO_BIN('{public_id}');")
            order_id = order_row[0]['id']
            before_pg = {r['pg_key'] for r in rows(env, 'pg', 'mock_pg', "SELECT JSON_OBJECT('pg_key',BIN_TO_UUID(pg_key)) FROM mock_payments;")}
            blocker = connection = None
            if boundary == 'payment_persisted':
                blocker, connection = await lock_sale(env, sale_id)
            else:
                proxy.arm('before' if boundary == 'lease' else 'after')
            async def pay():
                try:
                    status, body = await user.request('POST', f'/api/v1/orders/{public_id}/payment')
                    return {'http_status': status, 'body': body}
                except Exception as exc:
                    return {'error': type(exc).__name__}
            request = asyncio.create_task(pay())
            try:
                if boundary != 'payment_persisted':
                    if not await asyncio.to_thread(proxy.reached.wait, 2.5):
                        raise RuntimeError('controlled proxy boundary not reached')
                deadline = time.monotonic() + (10 if boundary == 'payment_persisted' else 1.5)
                observation = None
                while time.monotonic() < deadline:
                    state = rows(env, 'sales', 'limited_sale',
                        f"SELECT JSON_OBJECT('order_status',o.status,'lease_held',o.lease_token IS NOT NULL,"
                        f"'lease_until',o.lease_until,'payment_status',p.status,'pg_key',BIN_TO_UUID(p.pg_key)) "
                        f"FROM purchase_orders o JOIN payments p ON p.order_id=o.id WHERE o.id={order_id};")[0]
                    remote = rows(env, 'pg', 'mock_pg',
                        f"SELECT JSON_OBJECT('pg_key',BIN_TO_UUID(pg_key),'status',status,'amount',amount) FROM mock_payments WHERE pg_key=UUID_TO_BIN('{state['pg_key']}');")
                    wait_sql = ('SELECT COUNT(*) FROM performance_schema.data_lock_waits w '
                                'JOIN performance_schema.data_locks b ON b.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID '
                                'JOIN performance_schema.threads t ON t.THREAD_ID=b.THREAD_ID '
                                f"WHERE b.OBJECT_SCHEMA='limited_sale' AND b.OBJECT_NAME='sales' AND t.PROCESSLIST_ID={connection or 0};")
                    waits = int(env.mysql('sales', wait_sql))
                    suitable = state['lease_held'] and state['order_status'] == 'PAYMENT_PENDING'
                    if boundary == 'lease':
                        suitable &= not remote and proxy.observation['stage'] == 'before_forward'
                    elif boundary == 'pg_success':
                        suitable &= bool(remote) and remote[0]['status'] == 'SUCCEEDED' and state['payment_status'] != 'SUCCEEDED' and proxy.observation['http_status'] == 200
                    else:
                        suitable &= bool(remote) and remote[0]['status'] == 'SUCCEEDED' and state['payment_status'] == 'SUCCEEDED' and waits > 0
                    if suitable:
                        observation = {'at': wall(), 'profile_users': 100, 'profile_stock': 30, 'target_member_id': member, 'order_id': order_id, 'public_id': public_id,
                                       'sales_pid': sales_process.pid, 'state': state, 'pg': remote,
                                       'proxy': proxy.observation if boundary != 'payment_persisted' else None,
                                       'sale_lock_connection': connection, 'data_lock_waits': waits}
                        break
                    await asyncio.sleep(.03)
                if observation is None:
                    raise RuntimeError(f'{boundary} state not observed')
                if request.done():
                    raise RuntimeError('payment response completed before the JVM was killed')
                observation['client_request_completed_before_kill'] = False
                save(case_dir / 'boundary.json', observation)
                clock_start = sample()
                sales_process.send_signal(signal.SIGKILL)
                sales_process.wait(timeout=10)
                kill_time = time.monotonic()
                kill_wall = wall()
                if sales_process.returncode != -signal.SIGKILL:
                    raise RuntimeError('sales JVM death was not SIGKILL')
            finally:
                proxy.disarm()
                if blocker is not None:
                    unlock_sale(env, blocker, connection)
            request_result = await request
            if 'error' not in request_result:
                raise RuntimeError('interrupted request returned an HTTP response')
            observations = {}
            while time.monotonic() - kill_time <= 120:
                snap = collect(env, sale_id)
                snap['pg_payments'] = [p for p in snap['pg_payments'] if p['pg_key'] not in before_pg]
                if snap['orders'][0]['status'] in ('CONFIRMED', 'PAYMENT_FAILED', 'EXPIRED'):
                    observations[order_id] = time.monotonic() - kill_time
                    break
                await asyncio.sleep(1)
            await asyncio.sleep(1)
            final = collect(env, sale_id)
            final['pg_payments'] = [p for p in final['pg_payments'] if p['pg_key'] not in before_pg]
            clock_end = sample()
            save(case_dir / 'clock-guard.json', record(clock_start, clock_end))
            if final != snap:
                save(case_dir / 'unstable-before.json', snap)
                save(case_dir / 'unstable-after.json', final)
                raise RuntimeError('snapshot changed during quiescence check')
            req = {'attempt_id': 'order', 'member_id': member, 'key': key, 'sale_id': sale_id, 'quantity': 1,
                   'sent': True, 'result': 'CREATED', 'order_id': order_id, 'rejection_reason': None}
            document = {'final': True, 'manifest': manifest, 'requests': [req], **final,
                        'recovery': {'deadline_seconds': 120, 'end_elapsed_seconds': time.monotonic()-kill_time,
                                     'targets': [{'order_id': order_id, 'elapsed_seconds': observations.get(order_id)}]}}
            save(case_dir / 'snapshot.json', document)
            execution = {'kill_at': kill_wall, 'pid': sales_process.pid,
                         'exit_code': sales_process.returncode, 'payment_request': request_result,
                         'terminal_elapsed_seconds': observations.get(order_id)}
            errors = verify(document) + audit_boundary(boundary, observation, execution, document) + clock_errors(clock_start, clock_end)
            save(case_dir / 'execution.json', execution | {'verifier_errors': errors})
            if errors:
                raise RuntimeError(f'{boundary}-{iteration}: ' + '; '.join(errors))
            outcomes.append({'boundary': boundary, 'run': iteration, 'status': 'passed',
                             'terminal_elapsed_seconds': observations[order_id]})
            save(env.output / 'progress.json', outcomes)
            sales_process = env.start_sales('sales1', f'sales1-restart-{boundary}-{iteration}')
            env.health(env.ports['sales1'], sales_process)
    return outcomes


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    env = Isolated(args.output)
    proxy = ControlledProxy(0)
    proxy.start()
    env.sales1_proxy = f'http://127.0.0.1:{proxy.port}'
    try:
        env.start()
        proxy.target_port = env.ports['pg']
        results = asyncio.run(experiment(env, proxy))
        save(env.output / 'summary.json', {'status': 'passed', 'cases': results})
        print(json.dumps({'cases': len(results), 'status': 'passed'}))
    except Exception as exc:
        save(env.output / 'summary.json', {'status': 'failed', 'error': f'{type(exc).__name__}: {exc}'})
        print(f'FAILED: {type(exc).__name__}: {exc}', file=sys.stderr)
        sys.exit(1)
    finally:
        proxy.stop()
        env.close()
