#!/usr/bin/env python3
"""Stage 2-5: stop and restart Redis under order load and check the Redis failure policies against the database.

Each run builds a fresh isolated environment (two sales instances behind Caddy, Mock PG with every payment
succeeding, own MySQL and Redis, per-member order request limit switched on), prepares users before the load and
sends one order and, when created, one payment per user. Redis is stopped with docker stop part-way through the load
and started again after --outage-seconds. Clients retry with the same Idempotency-Key as the policy describes.
"""
import argparse
import asyncio
from collections import Counter
from datetime import datetime, timedelta, timezone
import json
import math
from pathlib import Path
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

from clock_guard import record, record_errors, sample
from http_client import Client, TruncatedResponse
from isolated import Isolated, ROOT, command
from load_metrics import combine_management, management_summary, parse_prometheus
from load_summary import Scenario, build_manifest, build_requests
from redis_outage import (CACHE_TTL_SECONDS, CACHE_VOLATILE_FIELDS, MAX_RELOGINS_PER_REQUEST, Backoff, Outcome,
                          cache_mismatches, classify, counter_deltas, decide, dirty_outside_results, evaluate, failures,
                          first_success_after, labelled_delta, lb_attempt_index, lb_error_entries, non_policy_failures,
                          parse_netstat, parse_retry_after, parse_timeout_seconds, probe_summary, server_record_check,
                          summarize_attempts, tcp_peaks, throughput, trailing_up, user_summary)
from run_balanced import INSTANCE_NAMES, reconciliation_rows
from run_baseline import Sampler, converge, management_snapshot
from run_oversubscription import expect, observe_sale_open, prepare_user, rows, save
from service_env import redis_timeout_settings, tomcat_metrics_setting
from snapshot import collect

DEFAULT_USERS = 1000
DEFAULT_STOCK = 300
DEFAULT_CONCURRENCY = 200
DEFAULT_OUTAGE_SECONDS = 15
DEFAULT_OUTAGE_AFTER = 0.25
DEFAULT_DEADLINE_SECONDS = 120
PROBE_INTERVAL_SECONDS = 0.5
HEALTH_INTERVAL_SECONDS = 0.5
REQUEST_TIMEOUT_SECONDS = 30
HEALTH_RECOVERY_WAIT_SECONDS = 60
CACHE_KEY = 'limited-sale:cache:sale:v1:{}'
METRIC_PREFIXES = ('order_rate_limit', 'sale_cache', 'lettuce', 'spring_data_redis')
RESULT_DIRECTORY = 'benchmarks/redis-outage/'


class Clock:
    """One monotonic zero for every timestamp of the run, with the wall time of that zero."""

    def __init__(self):
        self.mono0 = time.monotonic_ns()
        self.wall0 = datetime.now(timezone.utc)

    def now(self):
        return (time.monotonic_ns() - self.mono0) / 1e9

    def wall(self, seconds):
        return (self.wall0 + timedelta(seconds=seconds)).isoformat()


class Deadline:
    """Absolute limit for every user to finish, set when the outage ends (None until then)."""

    def __init__(self, clock):
        self.clock = clock
        self.at = None

    def expired(self):
        return self.at is not None and self.clock.now() > self.at

    def remaining(self):
        return float('inf') if self.at is None else max(0.0, self.at - self.clock.now())


class Experiment:
    def __init__(self, env, args, clock):
        self.env, self.args, self.clock = env, args, clock
        self.deadline = Deadline(clock)
        self.attempts = []
        self.flows = []
        self.done = 0
        self.outage = {}
        self.sale_id = None

    async def send(self, client, user, step, method, path, body=None, headers=None):
        """One HTTP request. Only status, business code, timing and Retry-After are kept: never cookies or CSRF."""
        seq = len(self.attempts) + 1
        record_ = {'seq': seq, 'user': user, 'step': step, 'method': method, 'status': None, 'code': None,
                   'retry_after': None, 'error': None}
        self.attempts.append(record_)
        started = self.clock.now()
        response, response_headers = None, {}
        try:
            record_['status'], response, response_headers = await client.request_full(
                method, path, body, {**(headers or {}), 'X-Attempt-Seq': str(seq)}, timeout=REQUEST_TIMEOUT_SECONDS)
        except TruncatedResponse as exc:
            record_.update(error='TruncatedResponse', status_seen=exc.status)
        except Exception as exc:
            record_['error'] = type(exc).__name__
        finished = self.clock.now()
        if isinstance(response, dict):
            record_['code'] = response.get('code')
        record_['retry_after'] = parse_retry_after(response_headers.get('retry-after'))
        record_.update(started_s=round(started, 4), finished_s=round(finished, 4),
                       latency_ms=round((finished - started) * 1000, 1))
        return Outcome(record_['status'], record_['code'], record_['retry_after'], record_['error']), response

    async def pause(self, seconds):
        await asyncio.sleep(min(seconds, self.deadline.remaining() + 0.05))

    async def relogin(self, user, client, backoff):
        """Drop the lost session, get a new CSRF token and log in again, backing off on 503 and 429."""
        client.cookies.clear()
        client.csrf = None
        streak = 0
        while not self.deadline.expired():
            steps = (('csrf', 'GET', '/api/v1/csrf', None),
                     ('login', 'POST', '/api/v1/sessions',
                      {'email': f'user{user}@example.test', 'password': self.env.password}),
                     ('csrf', 'GET', '/api/v1/csrf', None))
            for step, method, path, body in steps:
                outcome, response = await self.send(client, user, step, method, path, body)
                decision = decide(step, outcome)
                if decision.kind == 'fail':
                    raise RuntimeError(f'user {user}: {decision.result}')
                if decision.kind == 'retry':
                    streak += 1
                    await self.pause(decision.wait if decision.wait is not None else backoff.delay(streak))
                    break
                if step == 'csrf':
                    client.csrf = response['data']['token']
            else:
                return True
        return False

    async def flow(self, user, client, key, semaphore):
        result = {'user': user, 'result': None, 'retries': Counter(), 'relogins': 0, 'requests': 0}
        async with semaphore:
            try:
                await self.run_user(user, client, key, result)
            except RuntimeError as exc:
                result['result'] = f'FAILED:{exc}'
        result['finished_s'] = round(self.clock.now(), 4)
        self.flows.append(result)
        self.done += 1

    async def run_user(self, user, client, key, result):
        backoff = Backoff(seed=f'{self.args.seed}:{user}')
        step, order_id, streak, relogins = 'create', None, 0, 0
        while True:
            if self.deadline.expired():
                result['result'] = 'DEADLINE_EXCEEDED'
                return
            if step == 'create':
                outcome, response = await self.send(
                    client, user, 'create', 'POST', '/api/v1/orders', {'saleId': self.sale_id, 'quantity': 1},
                    {'Idempotency-Key': key})
            else:
                outcome, response = await self.send(
                    client, user, 'payment', 'POST', f'/api/v1/orders/{order_id}/payment')
            result['requests'] += 1
            decision = decide(step, outcome)
            if decision.kind == 'proceed':
                order_id, step, streak, relogins = response['data']['orderId'], 'payment', 0, 0
            elif decision.kind in ('finish', 'fail'):
                result['result'] = decision.result
                return
            elif decision.kind == 'retry':
                result['retries'][decision.reason] += 1
                streak = streak + 1 if decision.wait is None else 0
                await self.pause(decision.wait if decision.wait is not None else backoff.delay(streak))
            else:
                relogins += 1
                if relogins > MAX_RELOGINS_PER_REQUEST:
                    result['result'] = 'FAILED:RELOGIN_LOOP'
                    return
                result['retries'][decision.reason] += 1
                result['relogins'] += 1
                if not await self.relogin(user, client, backoff):
                    result['result'] = 'DEADLINE_EXCEEDED'
                    return

    async def probes(self, stop):
        """Public sale detail without cookies every interval, launched on a fixed schedule."""
        tasks, number = [], 0

        async def one(number_):
            await self.send(Client(self.env.ports['lb']), -1 - number_, 'probe', 'GET', f'/api/v1/sales/{self.sale_id}')
        while not stop.is_set():
            tasks.append(asyncio.create_task(one(number)))
            number += 1
            await asyncio.sleep(self.args.probe_interval)
        await asyncio.gather(*tasks)

    async def docker(self, action, container):
        started = self.clock.now()
        done = await asyncio.to_thread(subprocess.run, ['docker', action, container], capture_output=True, text=True)
        finished = self.clock.now()
        if done.returncode:
            raise RuntimeError(f'docker {action} failed: {done.stderr.strip()}')
        return started, finished

    async def outage_controller(self):
        """Stop Redis once enough users have finished, keep it down for the outage length, then start it again."""
        threshold = math.ceil(self.args.outage_after * self.args.users)
        while self.done < threshold:
            await asyncio.sleep(0.02)
        redis = self.env.containers[2]
        stop_issued, stop_returned = await self.docker('stop', redis)
        await asyncio.sleep(self.args.outage_seconds)
        start_issued, start_returned = await self.docker('start', redis)
        self.deadline.at = start_returned + self.args.deadline_seconds
        self.outage = {'users_finished_at_stop': threshold, 'stop_issued_s': stop_issued,
                       'stop_returned_s': stop_returned, 'start_issued_s': start_issued,
                       'start_returned_s': start_returned,
                       'redis_down_seconds': round(start_returned - stop_issued, 3),
                       'nominal_outage_seconds': self.args.outage_seconds}
        for name in ('stop_issued', 'stop_returned', 'start_issued', 'start_returned'):
            self.outage[name + '_wall'] = self.clock.wall(self.outage[name + '_s'])


class HealthMonitor:
    """Polls each instance's management health on a fixed interval; records UP, DOWN or UNREACHABLE."""

    def __init__(self, ports, clock, interval):
        self.ports, self.clock, self.interval = ports, clock, interval
        self.samples = []
        self.stopped = threading.Event()
        self.threads = [threading.Thread(target=self.loop, args=(name, port), daemon=True)
                        for name, port in ports.items()]

    def start(self):
        for thread in self.threads:
            thread.start()

    def loop(self, name, port):
        while not self.stopped.is_set():
            started = self.clock.now()
            status = 'UNREACHABLE'
            try:
                with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health', timeout=10) as response:
                    status = json.load(response).get('status', 'UNKNOWN')
            except urllib.error.HTTPError as exc:
                with exc:
                    try:
                        status = json.load(exc).get('status', 'DOWN')
                    except ValueError:
                        status = 'DOWN'
            except (OSError, ValueError):
                pass
            self.samples.append({'instance': name, 't': round(self.clock.now(), 3), 'status': status,
                                 'latency_ms': round((self.clock.now() - started) * 1000, 1)})
            self.stopped.wait(self.interval)

    def all_up_now(self):
        latest = {}
        for sample_ in list(self.samples):
            latest[sample_['instance']] = sample_['status']
        return set(latest) == set(self.ports) and all(s == 'UP' for s in latest.values())

    def finish(self):
        self.stopped.set()
        for thread in self.threads:
            thread.join(timeout=15)


class TcpMonitor:
    """Host socket counts by state once a second: ephemeral port exhaustion or a full accept queue would show here."""

    def __init__(self, ports, clock):
        self.ports, self.clock = ports, clock
        self.samples = []
        self.stopped = threading.Event()
        self.thread = threading.Thread(target=self.loop, daemon=True)

    def start(self):
        self.thread.start()

    def loop(self):
        while not self.stopped.is_set():
            try:
                text = subprocess.run(['netstat', '-an', '-p', 'tcp'], capture_output=True, text=True, timeout=20).stdout
            except (OSError, subprocess.TimeoutExpired):
                return
            self.samples.append({'t': round(self.clock.now(), 2), **parse_netstat(text, self.ports)})
            self.stopped.wait(1.0)

    def finish(self):
        self.stopped.set()
        self.thread.join(timeout=25)


def redis_cli(env, *args):
    return command('docker', 'exec', env.containers[2], 'redis-cli', *args)


def read_cache(env, sale_id):
    """The cached JSON and its remaining time to live in milliseconds; (None, None) when the key is absent."""
    key = CACHE_KEY.format(sale_id)
    ttl = int(redis_cli(env, 'PTTL', key))
    if ttl == -2:
        return None, None
    return json.loads(redis_cli(env, 'GET', key)), ttl


def sale_row(env, sale_id):
    """The sales row under the cache's JSON field names."""
    micro = "'%Y-%m-%dT%H:%i:%s.%fZ'"
    found = rows(env, 'sales', 'limited_sale',
                 f"SELECT JSON_OBJECT('saleId',id,'productId',product_id,'name',name,'description',description,"
                 f"'productNameSnapshot',product_name_snapshot,'unitPrice',unit_price,"
                 f"'startsAt',DATE_FORMAT(starts_at,{micro}),'endsAt',DATE_FORMAT(ends_at,{micro}),"
                 f"'totalQuantity',total_quantity,'perMemberLimit',per_member_limit,'soldQuantity',sold_quantity,"
                 f"'stopped',is_stopped,'version',version,'createdAt',DATE_FORMAT(created_at,{micro}),"
                 f"'updatedAt',DATE_FORMAT(updated_at,{micro})) FROM sales WHERE id={sale_id};")
    return found[0]


async def check_cache(env, sale_id, outage, clock):
    """After recovery the cache must be refilled with the database value.

    An entry written before the end of the outage, or a stale one (the policy allows order-driven fields to lag by up
    to the TTL), is waited out; the entry that is then regenerated by a public request is compared with MySQL.
    """
    result = {'initial_entry_present': False, 'initial_entry_written_after_recovery': None,
              'initial_mismatched_fields': [], 'waited_for_expiry_seconds': 0.0, 'public_get_status': None,
              'regenerated_after_recovery': False, 'final_mismatched_fields': ['entry missing']}
    cached, ttl = read_cache(env, sale_id)
    if cached is not None:
        age = CACHE_TTL_SECONDS - ttl / 1000
        written_after = age <= clock.now() - outage['start_returned_s'] + 1
        result.update(initial_entry_present=True, initial_entry_written_after_recovery=written_after,
                      initial_mismatched_fields=cache_mismatches(cached, sale_row(env, sale_id)))
        if written_after and not result['initial_mismatched_fields']:
            result.update(regenerated_after_recovery=True, final_mismatched_fields=[], public_get_status=None)
            return result
        waited = time.monotonic()
        while True:
            cached, ttl = read_cache(env, sale_id)
            if cached is None or time.monotonic() - waited > CACHE_TTL_SECONDS + 15:
                break
            await asyncio.sleep(min(max(ttl / 1000, 0.2), 5))
        result['waited_for_expiry_seconds'] = round(time.monotonic() - waited, 1)
    status, _ = await Client(env.ports['lb']).request('GET', f'/api/v1/sales/{sale_id}')
    result['public_get_status'] = status
    cached, ttl = read_cache(env, sale_id)
    if cached is not None:
        age = CACHE_TTL_SECONDS - ttl / 1000
        result['regenerated_after_recovery'] = age <= clock.now() - outage['start_returned_s'] + 1
        result['final_mismatched_fields'] = cache_mismatches(cached, sale_row(env, sale_id))
    return result


def git_dirty():
    porcelain = command('git', 'status', '--porcelain', '--untracked-files=all', cwd=ROOT)
    return dirty_outside_results(porcelain)


def environment_record(env, args):
    resources = json.loads((env.output / 'resources.json').read_text())
    keys = ('os', 'cpu_model', 'cpu_count', 'memory_bytes', 'java_version', 'mysql_version', 'redis_version',
            'docker_version', 'instances', 'head', 'jar_sha256', 'order_rate_limit_enabled', 'sales_extra_env',
            'fixed_redis_port')
    return {**{key: resources[key] for key in keys},
            'tomcat_mbean_registry_enabled_for_thread_metrics': True,
            'dirty': git_dirty(), 'dirty_note': f'tracked changes or untracked files outside {RESULT_DIRECTORY}',
            'redis_command_and_connect_timeout': args.redis_timeout,
            'redis_command_and_connect_timeout_seconds': parse_timeout_seconds(args.redis_timeout),
            'docker_vm_cpus': json.loads(subprocess.run(['docker', 'info', '--format', '{{json .}}'], text=True,
                                                        capture_output=True).stdout).get('NCPU'),
            'local_resources_shared': True}


def failing_samples(attempts, limit=8):
    """The first attempts outside the policy, with only the fields that explain them."""
    keys = ('seq', 'user', 'step', 'started_s', 'finished_s', 'latency_ms', 'status', 'status_seen', 'code', 'error',
            'instance')
    bad = [a for a in attempts if a['step'] != 'probe'
           and classify(Outcome(a.get('status'), a.get('code'), error=a.get('error'))) in ('other_5xx', 'connection_error')]
    return [{k: a.get(k) for k in keys if a.get(k) is not None} for a in bad[:limit]]


def combine_deltas(per_instance):
    merged = {}
    for rows_ in per_instance.values():
        for row in rows_:
            key = (row['name'], tuple(sorted(row['labels'].items())))
            merged[key] = merged.get(key, 0) + row['delta']
    return [{'name': name, 'labels': dict(labels), 'delta': round(delta, 6)}
            for (name, labels), delta in sorted(merged.items())]


async def wait_health(health, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if health.all_up_now():
            await asyncio.sleep(2 * HEALTH_INTERVAL_SECONDS + 0.5)
            return True
        await asyncio.sleep(0.5)
    return False


async def run_once(env, args, label):
    started = time.monotonic()
    phases = {}
    clock_start = sample()
    out = env.output
    users = args.users
    admin = Client(env.ports['lb'])
    await admin.login('admin@example.test', env.password)
    clients = [Client(env.ports['lb']) for _ in range(users)]
    limit = asyncio.Semaphore(args.prepare_concurrency)
    await asyncio.gather(*(prepare_user(client, i, env.password, limit) for i, client in enumerate(clients)))
    members = rows(env, 'sales', 'limited_sale',
                   "SELECT JSON_OBJECT('id',id,'email',email) FROM members WHERE role='USER';")
    by_email = {row['email']: row['id'] for row in members}
    if len(by_email) != users:
        raise RuntimeError('prepared user count mismatch')
    product = expect(*(await admin.request('POST', '/api/v1/admin/products',
                                           {'name': 'redis-outage', 'description': '', 'basePrice': 1000})),
                     201, 'product')
    start = datetime.now(timezone.utc) + timedelta(seconds=3)
    sale = expect(*(await admin.request('POST', '/api/v1/admin/sales',
                   {'productId': product['productId'], 'name': 'redis-outage', 'description': '', 'unitPrice': 1000,
                    'startsAt': start.isoformat(), 'endsAt': (start + timedelta(hours=1)).isoformat(),
                    'totalQuantity': args.stock, 'perMemberLimit': 1})), 201, 'sale')
    sale_id = sale['saleId']
    plan = [{'id': str(uuid.uuid4()), 'client': i, 'member_id': by_email[f'user{i}@example.test'],
             'key': str(uuid.uuid4())} for i in range(users)]
    scenario = Scenario(users=users, stock=args.stock, vus=args.concurrency)
    manifest = build_manifest(plan, sale_id, scenario)
    save(out / 'manifest.json', manifest)
    phases['prepare_seconds'] = round(time.monotonic() - started, 2)
    await observe_sale_open(env, sale_id, out)

    before = management_snapshot(env, out, 'before')
    sampler = Sampler({name: env.ports[name + '_management'] for name in env.instances})
    clock = Clock()
    experiment = Experiment(env, args, clock)
    experiment.sale_id = sale_id
    health = HealthMonitor({name: env.ports[name + '_management'] for name in env.instances}, clock,
                           HEALTH_INTERVAL_SECONDS)
    stop_probes = asyncio.Event()
    tcp = TcpMonitor([env.ports['lb'], *(env.ports[name] for name in env.instances)], clock)
    sampler.start()
    health.start()
    tcp.start()
    semaphore = asyncio.Semaphore(args.concurrency)
    flow_tasks = [asyncio.create_task(experiment.flow(p['client'], clients[p['client']], p['key'], semaphore))
                  for p in plan]
    controller = asyncio.create_task(experiment.outage_controller())
    prober = asyncio.create_task(experiment.probes(stop_probes))
    try:
        # Flows and the controller run together; any exception (for example a failed docker command) ends the run.
        pending = set(flow_tasks) | {controller}
        while pending:
            finished, pending = await asyncio.wait(pending, return_when=asyncio.FIRST_EXCEPTION)
            for task in finished:
                task.result()
    except BaseException:
        for task in (*flow_tasks, controller):
            task.cancel()
        raise
    finally:
        stop_probes.set()
        await prober
    load_end = clock.now()
    phases['load_seconds'] = round(load_end, 2)
    outage = experiment.outage
    flows = experiment.flows

    phase = time.monotonic()
    converged, phases['convergence_seconds'] = converge(env, sale_id, args.stock)
    health_up = await wait_health(health, HEALTH_RECOVERY_WAIT_SECONDS)
    cache = await check_cache(env, sale_id, outage, clock)
    after = management_snapshot(env, out, 'after')
    sampler.finish()
    health.finish()
    tcp.finish()
    save(out / 'tcp-samples.json', tcp.samples)
    save(out / 'management-samples.json', sampler.series)
    save(out / 'health-samples.json', health.samples)
    phases['post_load_seconds'] = round(time.monotonic() - phase, 2)

    phase = time.monotonic()
    final = collect(env, sale_id)
    requests = build_requests(plan, sale_id, final['request_results'])
    save(out / 'snapshot.json', {'final': True, 'manifest': manifest, 'requests': requests, **final, 'recovery': None})
    verifier = subprocess.run([sys.executable, '-B', str(ROOT / 'tools/order-regression/verify.py'),
                               str(out / 'snapshot.json')], text=True, capture_output=True)
    (out / 'verifier.log').write_text(verifier.stdout + verifier.stderr)
    verifier_violations = max(len([x for x in verifier.stderr.splitlines() if x.strip()]), 1) \
        if verifier.returncode else 0
    violations = reconciliation_rows(env)
    (out / 'reconciliation-violations.tsv').write_text('\n'.join(violations) + ('\n' if violations else ''))
    phases['verify_seconds'] = round(time.monotonic() - phase, 2)
    clock_guard = record(clock_start, sample())
    save(out / 'clock-guard.json', clock_guard)

    # Which instance served each attempt comes from the load balancer log, joined by the attempt header.
    access_log, caddy_log = env.load_balancer_logs()
    lb_errors = lb_error_entries(caddy_log)
    save(out / 'lb-errors.json', lb_errors)
    upstream_names = {upstream: name for upstream, name in zip(env.upstreams(), env.instances)}
    served = lb_attempt_index(access_log)
    unmatched = status_mismatch = 0
    for attempt in experiment.attempts:
        hit = served.get(attempt['seq'])
        attempt['instance'] = upstream_names.get(hit['upstream'], hit['upstream']) if hit else None
        unmatched += hit is None
        status_mismatch += hit is not None and attempt['status'] is not None and hit['status'] != attempt['status']
    save(out / 'attempts.json', experiment.attempts)
    save(out / 'flows.json', sorted(({**f, 'retries': dict(f['retries'])} for f in flows), key=lambda f: f['user']))
    save(out / 'lb-attempts.json', served)

    sale_final = final['sales'][0]
    summary_requests = summarize_attempts(experiment.attempts, outage)
    attempt_failures = non_policy_failures(experiment.attempts, outage)
    attempt_failures['samples'] = failing_samples(experiment.attempts)
    identities = {p['client']: {'member_id': p['member_id'], 'key': p['key']} for p in plan}
    server_records = server_record_check(identities, experiment.attempts, final['request_results'], final['orders'])
    instances = {name: management_summary(before[name], after[name], sampler.samples[name]) for name in env.instances}
    deltas = {name: counter_deltas(before[name], after[name], METRIC_PREFIXES) for name in env.instances}
    combined_deltas = combine_deltas(deltas)
    health_summary = trailing_up(health.samples, outage['start_returned_s'])
    flow_results = Counter(f['result'] for f in flows)
    client_429 = sum(a['status'] == 429 and a['code'] == 'ORDER_RATE_LIMITED' for a in experiment.attempts)
    probes = probe_summary(experiment.attempts, outage)
    cache['error_delta'] = labelled_delta(combined_deltas, 'sale_cache_requests_total', result='error')
    cache['requests_delta'] = {r: labelled_delta(combined_deltas, 'sale_cache_requests_total', result=r)
                               for r in ('hit', 'miss', 'error')}
    cache['evictions_delta'] = {r: labelled_delta(combined_deltas, 'sale_cache_evictions_total', result=r)
                                for r in ('success', 'error')}
    cache['volatile_fields_allowed_stale'] = list(CACHE_VOLATILE_FIELDS)
    result = {
        'users': users, 'stock': args.stock,
        'db': {'orders': len(final['orders']), 'confirmed': sum(o['status'] == 'CONFIRMED' for o in final['orders']),
               'remaining': sale_final['total_quantity'] - sale_final['reserved_quantity'] - sale_final['sold_quantity'],
               'converged': converged},
        'verifier_violations': verifier_violations, 'sql_violations': len(violations),
        'server_records': server_records, 'flows': dict(flow_results), 'attempt_failures': attempt_failures,
        'unavailable_seen_by_clients': sum(c.get('auth_unavailable', 0) for c in summary_requests['by_class'].values()),
        'probes': probes, 'cache': cache, 'client_429': client_429,
        'server_rate_limited': labelled_delta(combined_deltas, 'order_rate_limit_total', result='rejected'),
        'health': health_summary, 'clock_errors': record_errors(clock_guard),
    }
    checks = evaluate(result)
    result_failures = failures(checks)
    if not health_up:
        result_failures = list(dict.fromkeys(result_failures + ['management_health_up_after_recovery']))
    first_success = first_success_after(experiment.attempts, outage)
    phases['total_seconds'] = round(time.monotonic() - started, 2)
    by_step_instance = {step: dict(Counter(a.get('instance') for a in experiment.attempts if a['step'] == step))
                        for step in ('create', 'payment')}
    return {
        'scenario': 'redis-outage', 'label': label,
        'status': 'passed' if not result_failures else 'failed', 'failures': result_failures, 'checks': checks,
        'parameters': {'users': users, 'stock': args.stock, 'per_member_limit': 1, 'concurrency': args.concurrency,
                       'outage_seconds': args.outage_seconds, 'outage_after_fraction_of_users_finished': args.outage_after,
                       'deadline_seconds_after_outage': args.deadline_seconds, 'redis_timeout': args.redis_timeout,
                       'probe_interval_seconds': args.probe_interval, 'request_timeout_seconds': REQUEST_TIMEOUT_SECONDS,
                       'backoff': {'base_seconds': 0.25, 'factor': 2.0, 'cap_seconds': 1.0, 'jitter': 0.2,
                                   'seed': args.seed},
                       'max_relogins_per_request': MAX_RELOGINS_PER_REQUEST, 'order_rate_limit_enabled': True,
                       'sessions_prepared_before_load': True},
        'environment': environment_record(env, args),
        'outage': outage, 'requests': summary_requests, 'users': user_summary(flows),
        'throughput': throughput(experiment.attempts, flows, outage, load_end),
        'recovery': {'first_authenticated_success_seconds_after_outage_end': first_success,
                     'health': health_summary, 'health_up_wait_succeeded': health_up},
        'probes': probes, 'cache': cache, 'server_records': {k: v for k, v in server_records.items()},
        'db': result['db'], 'verifier_violations': verifier_violations, 'sql_violations': len(violations),
        'attempt_failures': attempt_failures,
        'load_balancer': {'requests_by_instance': by_step_instance, 'attempts_missing_from_access_log': unmatched,
                          'caddy_errors': {'count': len(lb_errors),
                                           'by_message': dict(Counter(e.get('error') or e.get('msg')
                                                                      for e in lb_errors).most_common(5)),
                                           'samples': lb_errors[:5]},
                          'status_differs_from_client': status_mismatch},
        'metrics': {'management': {'instances': instances, 'combined': combine_management(instances)},
                    'counter_deltas': {'combined': combined_deltas, 'instances': deltas},
                    'rate_limit_rejections': {'clients_saw_429': client_429,
                                              'server_rejected_delta': result['server_rate_limited']}},
        'host_tcp_peaks': tcp_peaks(tcp.samples),
        'durations_seconds': phases, 'started_at': env.started_at, 'run_id': env.run_id,
    }


def run_one(output, args, index, label):
    raw = Path(output) / 'raw' / f'run-{index}'
    env = Isolated(raw, instances=INSTANCE_NAMES, load_balancer=True, order_rate_limit=True,
                   sales_extra_env=redis_timeout_settings(args.redis_timeout) | tomcat_metrics_setting(),
                   fixed_redis_port=True)
    started = time.monotonic()
    try:
        env.start()
        summary = asyncio.run(run_once(env, args, label))
        summary['durations_seconds']['environment_start_seconds'] = round(time.monotonic() - started
                                                                          - summary['durations_seconds']['total_seconds'], 2)
    except Exception as exc:
        summary = {'scenario': 'redis-outage', 'status': 'failed', 'failures': [f'{type(exc).__name__}: {exc}'],
                   'error': f'{type(exc).__name__}: {exc}'}
        print(f'FAILED run {index}: {type(exc).__name__}: {exc}', file=sys.stderr)
    finally:
        env.close()
    summary['label'] = label
    summary['run'] = index
    summary['raw_directory'] = str(raw)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--redis-timeout', required=True,
                        help='Redis command and connect timeout of the sales JVMs, for example 1s or 2s')
    parser.add_argument('--output', required=True, help='results directory, for example benchmarks/redis-outage/timeout-1s')
    parser.add_argument('--runs', type=int, default=1)
    parser.add_argument('--users', type=int, default=DEFAULT_USERS)
    parser.add_argument('--stock', type=int, default=DEFAULT_STOCK)
    parser.add_argument('--concurrency', type=int, default=DEFAULT_CONCURRENCY,
                        help='users running at the same time')
    parser.add_argument('--outage-seconds', type=float, default=DEFAULT_OUTAGE_SECONDS)
    parser.add_argument('--outage-after', type=float, default=DEFAULT_OUTAGE_AFTER,
                        help='stop Redis when this fraction of users has a final result')
    parser.add_argument('--deadline-seconds', type=float, default=DEFAULT_DEADLINE_SECONDS,
                        help='every user must finish within this time after Redis is started again')
    parser.add_argument('--probe-interval', type=float, default=PROBE_INTERVAL_SECONDS)
    parser.add_argument('--prepare-concurrency', type=int, default=16)
    parser.add_argument('--seed', default='2-5', help='seed of the retry backoff jitter')
    parser.add_argument('--label', default=None)
    args = parser.parse_args()
    parse_timeout_seconds(args.redis_timeout)
    if args.runs < 1 or args.users < 1 or not 0 < args.stock <= args.users or not 0 < args.outage_after < 1:
        parser.error('invalid run size or outage trigger')
    label = args.label
    official = (args.users, args.stock, args.outage_seconds) == (DEFAULT_USERS, DEFAULT_STOCK, DEFAULT_OUTAGE_SECONDS)
    if not official:
        label = ' | '.join(filter(None, [label, f'UNOFFICIAL users={args.users} stock={args.stock} '
                                                 f'outage_seconds={args.outage_seconds}']))
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    exit_code = 0
    for index in range(1, args.runs + 1):
        target = output / f'run-{index}.json'
        if target.exists():
            parser.error(f'{target} already exists; use another --output')
        summary = run_one(output, args, index, label)
        save(target, summary)
        print(json.dumps({'run': index, 'status': summary['status'], 'failures': summary['failures'],
                          'summary': str(target)}), flush=True)
        exit_code |= summary['status'] != 'passed'
    return 1 if exit_code else 0


if __name__ == '__main__':
    sys.exit(main())
