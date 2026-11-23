#!/usr/bin/env python3
"""1-11 baseline load test: prepare users, run k6 through the load balancer, collect metrics and reconcile the DB.

Each run starts a fresh isolated environment (two sales instances behind Caddy, Mock PG, own MySQL and Redis),
so one command repeats the whole experiment and cleans only the resources it created.
"""
import argparse
import asyncio
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import resource
import statistics
import subprocess
import sys
import tempfile
import threading
import time
import uuid
import urllib.request
import zipfile

from balanced import parse_access_log, split, summarize_access_log
from clock_guard import record, sample
from http_client import Client
from isolated import Isolated, ROOT
from load_metrics import (SAMPLED_PREFIXES, combine_management, endpoint_requests, management_summary,
                          observe_delta, parse_observe, parse_prometheus, total)
from load_summary import ORDER_CREATE, SCENARIOS, TERMINAL, Scenario, aggregate, build_manifest, build_requests, \
    evaluate, failures
from run_balanced import INSTANCE_NAMES, reconciliation_rows
from run_oversubscription import expect, observe_sale_open, prepare_user, rows, save
from service_env import inherited_environment
from snapshot import collect

K6_SCRIPT = ROOT / 'tests/load/baseline-order.js'
OBSERVE_SCRIPT = ROOT / 'scripts/mysql-observe.sh'
SAMPLE_INTERVAL_SECONDS = 1.0
CONVERGENCE_DEADLINE_SECONDS = 180
K6_TIMEOUT_SECONDS = 20 * 60
LIB_MARKERS = ('micrometer-core', 'micrometer-registry-prometheus', 'prometheus-metrics-core', 'HikariCP',
               'tomcat-embed-core', 'mysql-connector')


def scrape(port, prefixes=None):
    with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/prometheus', timeout=10) as response:
        text = response.read().decode()
    return text, parse_prometheus(text, prefixes)


class Sampler(threading.Thread):
    """Scrape each management port during the load and convergence so gauge peaks (pool, CPU) are not missed."""

    def __init__(self, ports):
        super().__init__(daemon=True)
        self.ports = ports
        self.samples = {name: [] for name in ports}
        self.series = {name: [] for name in ports}
        self.started = time.monotonic()
        self.stopped = threading.Event()

    def run(self):
        while not self.stopped.is_set():
            for name, port in self.ports.items():
                try:
                    parsed = scrape(port, SAMPLED_PREFIXES)[1]
                except OSError:
                    continue
                self.samples[name].append(parsed)
                self.series[name].append({
                    'seconds': round(time.monotonic() - self.started, 2),
                    'hikari_active': total(parsed, 'hikaricp_connections_active'),
                    'hikari_pending': total(parsed, 'hikaricp_connections_pending'),
                    'process_cpu_usage': total(parsed, 'process_cpu_usage'),
                    'system_cpu_usage': total(parsed, 'system_cpu_usage')})
            self.stopped.wait(SAMPLE_INTERVAL_SECONDS)

    def finish(self):
        self.stopped.set()
        self.join(timeout=30)


def management_snapshot(env, output, label):
    snapshot = {}
    for name in env.instances:
        text, parsed = scrape(env.ports[name + '_management'])
        (output / f'management-{label}-{name}.prom').write_text(text)
        snapshot[name] = parsed
    return snapshot


def mysql_observe(env, output, label):
    # The password reaches the script only through the environment, as the script documents.
    environment = inherited_environment(os.environ) | {'MYSQL_PWD': env.password}
    done = subprocess.run([str(OBSERVE_SCRIPT), '--container', env.containers[0], '--user', 'root',
                           '--schema', 'limited_sale', '--top', '50'],
                          env=environment, text=True, capture_output=True)
    (output / f'mysql-observe-{label}.txt').write_text(done.stdout + done.stderr)
    return parse_observe(done.stdout)


def jar_libraries(jar):
    with zipfile.ZipFile(jar) as archive:
        names = [Path(name).name[:-4] for name in archive.namelist()
                 if name.startswith('BOOT-INF/lib/') and name.endswith('.jar')]
    return sorted(name for name in names if name.startswith(LIB_MARKERS))


def environment_record(env, label):
    resources = json.loads((env.output / 'resources.json').read_text())
    k6_version = subprocess.run(['k6', 'version'], text=True, capture_output=True).stdout.strip().splitlines()[0]
    docker = json.loads(subprocess.run(['docker', 'info', '--format', '{{json .}}'], text=True,
                                       capture_output=True).stdout)
    keys = ('os', 'cpu_model', 'cpu_count', 'memory_bytes', 'java_version', 'mysql_version', 'redis_version',
            'docker_version', 'instances', 'open_file_limit', 'head', 'dirty', 'jar_sha256',
            'order_rate_limit_enabled')
    return {**{key: resources[key] for key in keys}, 'k6_version': k6_version,
            'docker_vm': {'cpus': docker.get('NCPU'), 'memory_bytes': docker.get('MemTotal')},
            'sales_jar_libraries': jar_libraries(ROOT / 'build/libs/limited-sale-0.0.1-SNAPSHOT.jar'),
            'label': label}


async def prepare_sessions(env, scenario, concurrency):
    """Register and log in every user through the load balancer; returns the logged-in clients."""
    clients = [Client(env.ports['lb']) for _ in range(scenario.users)]
    limit = asyncio.Semaphore(concurrency)
    done = 0

    async def prepare(index):
        nonlocal done
        await prepare_user(clients[index], index, env.password, limit)
        done += 1
        if done % 1000 == 0:
            print(f'prepared {done}/{scenario.users} users', file=sys.stderr, flush=True)
    await asyncio.gather(*(prepare(i) for i in range(scenario.users)))
    return clients


def write_sessions(clients, attempts):
    """Write cookies and CSRF tokens to a private temporary file that the caller deletes after k6 ends."""
    handle, path = tempfile.mkstemp(prefix='load-sessions-', suffix='.json')
    os.chmod(path, 0o600)
    with os.fdopen(handle, 'w') as stream:
        json.dump([{'cookies': clients[a['client']].cookies, 'csrf': clients[a['client']].csrf, 'key': a['key']}
                   for a in attempts], stream)
    return path


def run_k6(env, scenario, sale_id, sessions_path, summary_path, log_path):
    k6_env = inherited_environment(os.environ) | {
        'K6_NO_USAGE_REPORT': 'true', 'ITERATIONS': str(scenario.users), 'VUS': str(scenario.vus),
        'SALE_ID': str(sale_id), 'SESSIONS_FILE': sessions_path, 'SUMMARY_PATH': str(summary_path),
        'BASE_URL': f"http://127.0.0.1:{env.ports['lb']}"}
    with open(log_path, 'w') as log:
        done = subprocess.run(['k6', 'run', '--no-color', '--quiet', str(K6_SCRIPT)], env=k6_env, stdout=log,
                              stderr=subprocess.STDOUT, timeout=K6_TIMEOUT_SECONDS)
    summary = json.loads(Path(summary_path).read_text()) if Path(summary_path).exists() else None
    return done.returncode, summary


def order_counts(env, sale_id):
    found = rows(env, 'sales', 'limited_sale',
                 f"SELECT JSON_OBJECT('total',COUNT(*),'terminal',COALESCE(SUM(status IN "
                 f"({','.join(repr(s) for s in TERMINAL)})),0)) FROM purchase_orders WHERE sale_id={sale_id};")
    return found[0]


def converge(env, sale_id, expected_orders):
    """Wait until every created order is terminal; payment recovery can finish some after the load ends."""
    started = time.monotonic()
    deadline = started + CONVERGENCE_DEADLINE_SECONDS
    while True:
        counts = order_counts(env, sale_id)
        if counts['total'] == counts['terminal'] and counts['total'] == expected_orders:
            return True, round(time.monotonic() - started, 2)
        if time.monotonic() >= deadline:
            return False, round(time.monotonic() - started, 2)
        time.sleep(1)


async def run_once(env, scenario_name, scenario, label, prepare_concurrency):
    phases = {}
    started = time.monotonic()
    clock_start = sample()
    admin = Client(env.ports['lb'])
    await admin.login('admin@example.test', env.password)
    phase = time.monotonic()
    clients = await prepare_sessions(env, scenario, prepare_concurrency)
    members = rows(env, 'sales', 'limited_sale',
                   "SELECT JSON_OBJECT('id',id,'email',email) FROM members WHERE role='USER';")
    by_email = {row['email']: row['id'] for row in members}
    if len(by_email) != scenario.users:
        raise RuntimeError('prepared user count mismatch')
    attempts = [{'id': str(uuid.uuid4()), 'client': i, 'member_id': by_email[f'user{i}@example.test'],
                 'key': str(uuid.uuid4())} for i in range(scenario.users)]
    product = expect(*(await admin.request('POST', '/api/v1/admin/products',
                                           {'name': 'baseline', 'description': '', 'basePrice': 1000})), 201, 'product')
    start = datetime.now(timezone.utc) + timedelta(seconds=3)
    sale = expect(*(await admin.request('POST', '/api/v1/admin/sales',
                   {'productId': product['productId'], 'name': 'baseline', 'description': '', 'unitPrice': 1000,
                    'startsAt': start.isoformat(), 'endsAt': (start + timedelta(hours=1)).isoformat(),
                    'totalQuantity': scenario.stock, 'perMemberLimit': 1})), 201, 'sale')
    sale_id = sale['saleId']
    manifest = build_manifest(attempts, sale_id, scenario)
    save(env.output / 'manifest.json', manifest)
    phases['prepare_seconds'] = round(time.monotonic() - phase, 2)
    await observe_sale_open(env, sale_id, env.output)

    management_before = management_snapshot(env, env.output, 'before')
    observe_before = mysql_observe(env, env.output, 'before')
    sessions_path = write_sessions(clients, attempts)
    sampler = Sampler({name: env.ports[name + '_management'] for name in env.instances})
    k6_summary_path = env.output / 'k6-summary.json'
    try:
        sampler.start()
        phase = time.monotonic()
        cpu_before = resource.getrusage(resource.RUSAGE_CHILDREN)
        exit_code, k6_summary = run_k6(env, scenario, sale_id, sessions_path, k6_summary_path,
                                       env.output / 'k6.log')
        cpu_after = resource.getrusage(resource.RUSAGE_CHILDREN)
        phases['k6_seconds'] = round(time.monotonic() - phase, 2)
    except BaseException:
        sampler.finish()
        raise
    finally:
        # Sessions are credentials: they never leave this temporary file, and it is removed even on failure.
        if os.path.exists(sessions_path):
            os.unlink(sessions_path)
    if k6_summary is None:
        raise RuntimeError(f'k6 exit code {exit_code} and no summary; see k6.log')
    k6_summary['exit_code'] = exit_code

    # k6 is the only child process that ended inside the measured window, so its CPU time is the difference.
    k6_cpu = cpu_after.ru_utime + cpu_after.ru_stime - cpu_before.ru_utime - cpu_before.ru_stime
    load_generator = {'cpu_seconds': round(k6_cpu, 2), 'cpu_cores_mean': round(k6_cpu / phases['k6_seconds'], 2)}
    created = k6_summary['business_codes'].get('ORDER_CREATED', 0)
    converged, phases['convergence_seconds'] = converge(env, sale_id, created)
    management_after = management_snapshot(env, env.output, 'after')
    sampler.finish()
    save(env.output / 'management-samples.json', sampler.series)
    observe_after = mysql_observe(env, env.output, 'after')

    phase = time.monotonic()
    final = collect(env, sale_id)
    requests = build_requests(attempts, sale_id, final['request_results'])
    save(env.output / 'snapshot.json', {'final': True, 'manifest': manifest, 'requests': requests, **final,
                                         'recovery': None})
    verifier = subprocess.run([sys.executable, '-B', str(ROOT / 'tools/order-regression/verify.py'),
                               str(env.output / 'snapshot.json')], text=True, capture_output=True)
    (env.output / 'verifier.log').write_text(verifier.stdout + verifier.stderr)
    verifier_violations = max(len([x for x in verifier.stderr.splitlines() if x.strip()]), 1) \
        if verifier.returncode else 0
    violations = reconciliation_rows(env)
    (env.output / 'reconciliation-violations.tsv').write_text('\n'.join(violations) + ('\n' if violations else ''))
    phases['verify_seconds'] = round(time.monotonic() - phase, 2)
    save(env.output / 'clock-guard.json', record(clock_start, sample()))

    access_log, _ = env.load_balancer_logs()
    access = summarize_access_log(parse_access_log(access_log))
    sale_row = final['sales'][0]
    instances = {name: management_summary(management_before[name], management_after[name], sampler.samples[name])
                 for name in env.instances}
    # The upstream label in the access log is host:port; the management counters give the same split per instance.
    order_create_by_instance = {name: endpoint_requests(summary, *ORDER_CREATE) for name, summary in instances.items()}
    mysql = observe_delta(observe_before, observe_after)
    result = {
        'k6': k6_summary,
        'db': {'orders': len(final['orders']), 'confirmed': sum(o['status'] == 'CONFIRMED' for o in final['orders']),
               'remaining': sale_row['total_quantity'] - sale_row['reserved_quantity'] - sale_row['sold_quantity'],
               'converged': converged},
        'verifier_violations': verifier_violations, 'sql_violations': len(violations),
        'order_create_by_instance': order_create_by_instance,
        'mysql_observe_complete': not mysql['unavailable_sections'],
    }
    checks = evaluate(result, scenario)
    phases['total_seconds'] = round(time.monotonic() - started, 2)
    return {
        'scenario': scenario_name, 'label': None, 'status': 'passed' if not failures(checks) else 'failed',
        'users': scenario.users, 'stock': scenario.stock, 'vus': scenario.vus,
        'checks': checks, 'failures': failures(checks),
        'k6': k6_summary, 'load_generator': load_generator, 'db': result['db'],
        'verifier_violations': verifier_violations, 'sql_violations': len(violations),
        'order_create_by_instance': order_create_by_instance,
        'load_balancer_access': {'order_create_split': split(access['order_creates']),
                                 'order_payment_split': split(access['order_payments']),
                                 'auth_failures': access['auth_failures']},
        'management': {'instances': instances, 'combined': combine_management(instances)},
        'mysql': mysql, 'durations_seconds': phases,
        'started_at': env.started_at, 'run_id': env.run_id,
    }


def run_one(output, scenario_name, scenario, index, label, prepare_concurrency):
    """One fresh environment and one measured run; the result is always written, passed or failed."""
    raw = Path(output) / 'raw' / f'{scenario_name}-run-{index}'
    env = Isolated(raw, instances=INSTANCE_NAMES, load_balancer=True)
    started = time.monotonic()
    try:
        env.start()
        env_seconds = round(time.monotonic() - started, 2)
        summary = asyncio.run(run_once(env, scenario_name, scenario, label, prepare_concurrency))
        summary['durations_seconds']['environment_start_seconds'] = env_seconds
        summary['environment'] = environment_record(env, label)
    except Exception as exc:
        summary = {'scenario': scenario_name, 'status': 'failed', 'failures': [f'{type(exc).__name__}: {exc}'],
                   'error': f'{type(exc).__name__}: {exc}', 'users': scenario.users, 'stock': scenario.stock}
        print(f'FAILED run {index}: {type(exc).__name__}: {exc}', file=sys.stderr)
    finally:
        env.close()
    summary['label'] = label
    summary['run'] = index
    summary['durations_seconds_total_including_cleanup'] = round(time.monotonic() - started, 2)
    return summary


def aggregate_file(scenario_name, label, summaries):
    complete = [s for s in summaries if 'k6' in s]
    return {'scenario': scenario_name, 'label': label, 'runs': len(summaries),
            'passed': bool(summaries) and all(s['status'] == 'passed' for s in summaries),
            'failures': {f'run-{s["run"]}': s['failures'] for s in summaries if s['failures']},
            'environment': complete[0]['environment'] if complete else None,
            'metrics': aggregate(complete) if complete else {},
            'median_total_run_seconds': statistics.median(s['durations_seconds']['total_seconds']
                                                          for s in complete) if complete else None}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenario', choices=sorted(SCENARIOS), required=True)
    parser.add_argument('--runs', type=int, default=1)
    parser.add_argument('--output', required=True, help='results directory; created if missing')
    parser.add_argument('--label', default=None,
                        help='free text stored in every result, for example a rehearsal with substituted dependencies')
    parser.add_argument('--prepare-concurrency', type=int, default=16)
    parser.add_argument('--users', type=int, help='smoke-test override of the scenario; marks the result unofficial')
    parser.add_argument('--stock', type=int, help='smoke-test override of the scenario; marks the result unofficial')
    parser.add_argument('--vus', type=int, help='smoke-test override of the scenario; marks the result unofficial')
    args = parser.parse_args()
    if args.runs < 1:
        parser.error('--runs must be at least 1')
    policy = SCENARIOS[args.scenario]
    scenario = Scenario(users=args.users or policy.users, stock=args.stock or policy.stock, vus=args.vus or policy.vus)
    label = args.label
    if scenario != policy:
        label = ' | '.join(filter(None, [label, f'UNOFFICIAL override {scenario}']))
    output = Path(args.output)
    aggregate_path = output / f'{args.scenario}-aggregate.json'
    if aggregate_path.exists():
        parser.error(f'{aggregate_path} already exists; use another --output')
    output.mkdir(parents=True, exist_ok=True)
    summaries = []
    for index in range(1, args.runs + 1):
        summary = run_one(output, args.scenario, scenario, index, label, args.prepare_concurrency)
        save(output / f'{args.scenario}-run-{index}.json', summary)
        summaries.append(summary)
        print(json.dumps({'run': index, 'status': summary['status'], 'failures': summary['failures'],
                          'throughput': summary.get('k6', {}).get('http_requests_per_second')}), flush=True)
    result = aggregate_file(args.scenario, label, summaries)
    save(aggregate_path, result)
    print(json.dumps({'scenario': args.scenario, 'passed': result['passed'], 'failures': result['failures']}))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
