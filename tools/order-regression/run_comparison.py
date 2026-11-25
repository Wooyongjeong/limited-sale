#!/usr/bin/env python3
"""Comparison runs: sale detail cache off/on (2-6 A), order request limit off/on with abusive users (2-6 B) and the
Redis sold-out pre-check off/on (2-7 C).

Each run starts a fresh isolated environment (two sales instances behind Caddy, Mock PG, own MySQL and Redis) like the
1-11 baseline and reuses its metric collection, verifier and reconciliation SQL. The four conditions run interleaved
round by round and the order is recorded.
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
import time
from types import SimpleNamespace
import uuid

from balanced import parse_access_log, split, summarize_access_log
from clock_guard import record, sample
from comparison import (CONDITIONS, DEFAULT_CONDITIONS, FINAL_DEADLINE_SECONDS, abuse_attempts, abuse_server_summary,
                        aggregate, async_db_summary, build_manifest, cache_counts, derived, evaluate, evaluate_async,
                        evaluate_v1_fault,
                        execution_order, failures, gate_counts, gate_update_counts, labeled_deltas, policy_plan,
                        rate_limit_counts, verifier_requests, with_overrides)
from http_client import Client
from isolated import Isolated, ROOT
from load_metrics import combine_management, endpoint_requests, management_summary, observe_delta
from load_summary import ORDER_CREATE
from run_balanced import INSTANCE_NAMES, reconciliation_rows
from run_baseline import (K6_TIMEOUT_SECONDS, Sampler, converge, environment_record, management_snapshot,
                          mysql_observe, prepare_sessions)
from run_oversubscription import expect, observe_sale_open, rows, save
from service_env import inherited_environment, order_rate_limit_max_requests_setting
from snapshot import collect

K6_SCRIPT = ROOT / 'tests/load/comparison-order.js'
# Stage 3-8: accept, poll and pay through the v2 API.
K6_ASYNC_SCRIPT = ROOT / 'tests/load/async-order.js'
CACHE_SWITCH = 'LIMITED_SALE_SALE_CACHE_ENABLED'
GATE_SWITCH = 'LIMITED_SALE_SALE_SOLD_OUT_GATE_ENABLED'
# The sales JVMs use these application defaults except the three switches and max-requests, which every condition
# passes explicitly; the values are recorded from the code defaults (RedisSaleCache, RedisOrderRateLimit,
# RedisSoldOutGate) rather than read back from the JVMs. The max-requests default was 5 until stage 2-6 b3 and is 1 now.
APPLICATION_DEFAULTS = {'sale_cache_ttl': 'PT60S', 'order_rate_limit_max_requests': 1, 'order_rate_limit_window': '1s',
                        'sold_out_gate_ttl': 'PT5S'}


def write_sessions(clients, normal, plan):
    """Cookies and CSRF tokens of the normal users, then the abusive users, in a private file k6 reads."""
    handle, path = tempfile.mkstemp(prefix='comparison-sessions-', suffix='.json')
    os.chmod(path, 0o600)
    entries = [{'cookies': clients[a['client']].cookies, 'csrf': clients[a['client']].csrf, 'key': a['key']}
               for a in normal]
    entries += [{'cookies': clients[plan.users + j].cookies, 'csrf': clients[plan.users + j].csrf, 'key': ''}
                for j in range(plan.abusers)]
    with os.fdopen(handle, 'w') as stream:
        json.dump(entries, stream)
    return path


def run_k6(env, plan, sale_id, sessions_path, summary_path, log_path, script=K6_SCRIPT, retries=0):
    k6_env = inherited_environment(os.environ) | {
        'K6_NO_USAGE_REPORT': 'true', 'ITERATIONS': str(plan.users), 'VUS': str(plan.vus),
        'DETAIL_VIEWS': str(plan.detail_views), 'ABUSERS': str(plan.abusers),
        'ABUSER_VUS': str(plan.abuser_vus_per_member), 'SALE_ID': str(sale_id),
        'SESSIONS_FILE': sessions_path, 'SUMMARY_PATH': str(summary_path), 'RETRIES': str(retries),
        'BASE_URL': f"http://127.0.0.1:{env.ports['lb']}"}
    with open(log_path, 'w') as log:
        done = subprocess.run(['k6', 'run', '--no-color', '--quiet', str(script)], env=k6_env, stdout=log,
                              stderr=subprocess.STDOUT, timeout=K6_TIMEOUT_SECONDS)
    summary = json.loads(Path(summary_path).read_text()) if Path(summary_path).exists() else None
    return done.returncode, summary


def dirty_outside(results_root):
    """Working-tree changes other than the result files this tool itself writes under the output directory."""
    status = subprocess.run(['git', 'status', '--porcelain'], cwd=ROOT, text=True, capture_output=True).stdout
    try:
        prefix = str(Path(results_root).resolve().relative_to(ROOT))
    except ValueError:
        prefix = None
    paths = [line[3:].strip().strip('"') for line in status.splitlines()]
    return any(not (prefix and path.startswith(prefix)) for path in paths)


ACCEPTANCE_ROWS = """
SELECT JSON_OBJECT('id', a.id, 'status', a.status, 'rejection_reason', a.rejection_reason,
  'failure_reason', a.failure_reason,
  'accepted_at_us', TIMESTAMPDIFF(MICROSECOND, '2000-01-01', a.accepted_at),
  'finished_at_us', IF(a.finished_at IS NULL, NULL, TIMESTAMPDIFF(MICROSECOND, '2000-01-01', a.finished_at)),
  'completion_ms', IF(a.finished_at IS NULL, NULL, TIMESTAMPDIFF(MICROSECOND, a.accepted_at, a.finished_at) / 1000),
  'sends', (SELECT COUNT(*) FROM order_outbox o WHERE o.acceptance_id = a.id),
  'orders', (SELECT COUNT(*) FROM purchase_orders p WHERE p.member_id = a.member_id AND p.request_key = a.public_id))
FROM order_acceptances a WHERE a.sale_id = {sale_id};
"""


def acceptance_rows(env, sale_id):
    """One row per acceptance of the sale; 'consumed' means it had an outbox row, so the consumer decided it."""
    found = rows(env, 'sales', 'limited_sale', ACCEPTANCE_ROWS.format(sale_id=int(sale_id)))
    for row in found:
        row['consumed'] = row['sends'] > 0
        row['completion_ms'] = None if row['completion_ms'] is None else float(row['completion_ms'])
    return found


def unfinished_acceptances(env, sale_id):
    return int(env.mysql('sales', f"SELECT COUNT(*) FROM limited_sale.order_acceptances WHERE sale_id={int(sale_id)} "
                                  "AND status='ACCEPTED';"))


def wait_final(env, sale_id, deadline_seconds=FINAL_DEADLINE_SECONDS):
    """Wait until no acceptance of the sale is ACCEPTED; returns (reached in time, seconds waited)."""
    started = time.monotonic()
    while True:
        if unfinished_acceptances(env, sale_id) == 0:
            return True, round(time.monotonic() - started, 2)
        if time.monotonic() - started >= deadline_seconds:
            return False, round(time.monotonic() - started, 2)
        time.sleep(1)


def tolerant_snapshot(env, output, label, before):
    """Like management_snapshot, for fault runs: an instance that does not answer (killed and not restarted) keeps
    its earlier snapshot, so its counters show no change instead of failing the run."""
    snapshot, missing = {}, []
    for name in env.instances:
        try:
            snapshot[name] = management_snapshot(SimpleNamespace(instances=(name,), ports=env.ports), output,
                                                 label)[name]
        except OSError:
            snapshot[name] = before[name]
            missing.append(name)
    if missing:
        (output / f'management-{label}-missing.txt').write_text('\n'.join(missing) + '\n')
    return snapshot


def completion_recorded(before, after, instances):
    """Acceptances the consumers recorded in order_acceptance_completion_seconds, over all instances."""
    return int(round(sum(sum(labeled_deltas(before[n], after[n], 'order_acceptance_completion_seconds_count').values())
                         for n in instances)))


async def run_once(env, condition, plan, label, prepare_concurrency, fault=None):
    phases = {}
    started = time.monotonic()
    clock_start = sample()
    admin = Client(env.ports['lb'])
    await admin.login('admin@example.test', env.password)
    phase = time.monotonic()
    everyone = plan.users + plan.abusers
    clients = await prepare_sessions(env, SimpleNamespace(users=everyone), prepare_concurrency)
    members = rows(env, 'sales', 'limited_sale',
                   "SELECT JSON_OBJECT('id',id,'email',email) FROM members WHERE role='USER';")
    by_email = {row['email']: row['id'] for row in members}
    if len(by_email) != everyone:
        raise RuntimeError('prepared user count mismatch')
    normal = [{'id': str(uuid.uuid4()), 'client': i, 'member_id': by_email[f'user{i}@example.test'],
               'key': str(uuid.uuid4())} for i in range(plan.users)]
    abuser_ids = {by_email[f'user{plan.users + j}@example.test'] for j in range(plan.abusers)}
    product = expect(*(await admin.request('POST', '/api/v1/admin/products',
                                           {'name': 'comparison', 'description': '', 'basePrice': 1000})), 201,
                     'product')
    start = datetime.now(timezone.utc) + timedelta(seconds=3)
    sale = expect(*(await admin.request('POST', '/api/v1/admin/sales',
                   {'productId': product['productId'], 'name': 'comparison', 'description': '', 'unitPrice': 1000,
                    'startsAt': start.isoformat(), 'endsAt': (start + timedelta(hours=1)).isoformat(),
                    'totalQuantity': plan.stock, 'perMemberLimit': 1})), 201, 'sale')
    sale_id = sale['saleId']
    phases['prepare_seconds'] = round(time.monotonic() - phase, 2)
    await observe_sale_open(env, sale_id, env.output)

    management_before = management_snapshot(env, env.output, 'before')
    observe_before = mysql_observe(env, env.output, 'before')
    sessions_path = write_sessions(clients, normal, plan)
    sampler = Sampler({name: env.ports[name + '_management'] for name in env.instances})
    k6_summary_path = env.output / 'k6-summary.json'
    v2 = condition.api == 'v2'
    try:
        sampler.start()
        phase = time.monotonic()
        cpu_before = resource.getrusage(resource.RUSAGE_CHILDREN)
        if fault is not None:
            fault.start(env, sale_id)
        # The v2 script always retries 5xx; the v1 script only in fault runs, so comparison runs stay as measured.
        exit_code, k6_summary = run_k6(env, plan, sale_id, sessions_path, k6_summary_path, env.output / 'k6.log',
                                       K6_ASYNC_SCRIPT if v2 else K6_SCRIPT, 30 if fault is not None else 0)
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

    k6_cpu = cpu_after.ru_utime + cpu_after.ru_stime - cpu_before.ru_utime - cpu_before.ru_stime
    load_generator = {'cpu_seconds': round(k6_cpu, 2), 'cpu_cores_mean': round(k6_cpu / phases['k6_seconds'], 2)}
    if v2:
        # Every acceptance must be final within the deadline (3-1 #8); the orders are the ORDER_CREATED ones.
        all_final, phases['final_seconds_after_load'] = wait_final(env, sale_id)
        if fault is not None:
            fault.after_final(env, sale_id)
            fault.join()
            all_final_again, extra = wait_final(env, sale_id)
            all_final = all_final and all_final_again
            phases['final_seconds_after_fault'] = extra
        created = int(env.mysql('sales', f"SELECT COUNT(*) FROM limited_sale.purchase_orders WHERE sale_id={sale_id};"))
    elif fault is not None:
        fault.after_final(env, sale_id)
        fault.join()
        # Retries may find an order again (ORDER_FOUND), so the database is the count of orders here.
        created = int(env.mysql('sales', f"SELECT COUNT(*) FROM limited_sale.purchase_orders WHERE sale_id={sale_id};"))
    else:
        created = sum(k6_summary['endpoints'][name]['business_codes'].get('ORDER_CREATED', 0)
                      for name in ('order_create', 'abuse_order_create'))
    converged, phases['convergence_seconds'] = converge(env, sale_id, created)
    management_after = (management_snapshot(env, env.output, 'after') if fault is None
                        else tolerant_snapshot(env, env.output, 'after', management_before))
    sampler.finish()
    save(env.output / 'management-samples.json', sampler.series)
    observe_after = mysql_observe(env, env.output, 'after')

    phase = time.monotonic()
    final = collect(env, sale_id)
    abuse = abuse_attempts(abuser_ids, final['request_results'])
    manifest = build_manifest(normal, abuse, sale_id, plan)
    save(env.output / 'manifest.json', manifest)
    if v2:
        # verify.py reads order_request_results, which the v2 path never writes; the acceptance rows and the
        # reconciliation SQL (ACCEPTANCE_VS_ORDER and the rest) are its checks instead.
        store_rows = acceptance_rows(env, sale_id)
        save(env.output / 'acceptances.json', store_rows)
        verifier_violations = 0
    else:
        requests = verifier_requests(normal, abuse, sale_id, final['request_results'])
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

    access_log, lb_errors = env.load_balancer_logs()
    # Caddy's own messages say why a request got 502 (stage 4-3); the access log is summarised below.
    (env.output / 'lb-errors.log').write_text(lb_errors)
    access = summarize_access_log(parse_access_log(access_log))
    sale_row = final['sales'][0]
    instances = {name: management_summary(management_before[name], management_after[name], sampler.samples[name])
                 for name in env.instances}
    create_endpoint = ('POST', '/api/v2/orders') if v2 else ORDER_CREATE
    order_create_by_instance = {name: endpoint_requests(summary, *create_endpoint)
                                for name, summary in instances.items()}
    mysql = observe_delta(observe_before, observe_after)
    cache = cache_counts([labeled_deltas(management_before[n], management_after[n], 'sale_cache_requests_total')
                          for n in env.instances])
    gate = gate_counts([labeled_deltas(management_before[n], management_after[n],
                                       'sale_sold_out_gate_requests_total') for n in env.instances])
    gate_updates = gate_update_counts([labeled_deltas(management_before[n], management_after[n],
                                                      'sale_sold_out_gate_updates_total') for n in env.instances])
    rate_limit = rate_limit_counts([labeled_deltas(management_before[n], management_after[n],
                                                   'order_rate_limit_total') for n in env.instances])
    abuse_server = abuse_server_summary(abuser_ids, final['request_results'], final['orders'])
    confirmed = sum(o['status'] == 'CONFIRMED' for o in final['orders'])
    db = {'orders': len(final['orders']), 'confirmed': confirmed, 'confirmed_abuse': abuse_server['confirmed'],
          'confirmed_normal': confirmed - abuse_server['confirmed'],
          'remaining': sale_row['total_quantity'] - sale_row['reserved_quantity'] - sale_row['sold_quantity'],
          'converged': converged, 'request_results': len(final['request_results'])}
    result = {
        'k6': k6_summary, 'db': db, 'verifier_violations': verifier_violations, 'sql_violations': len(violations),
        'order_create_by_instance': order_create_by_instance, 'mysql_observe_complete': not mysql['unavailable_sections'],
        'abuse': abuse_server, 'cache': cache, 'rate_limit': rate_limit,
        'cache_metrics_present': any(labeled_deltas({}, management_after[n], 'sale_cache_requests_total')
                                     for n in env.instances),
        'gate': gate, 'gate_updates': gate_updates,
        # The disabled gate registers no metric at all, so the series' presence shows which switch the JVMs ran with.
        'gate_metrics_present': any(labeled_deltas({}, management_after[n], 'sale_sold_out_gate_requests_total')
                                    for n in env.instances),
    }
    store = None
    if v2:
        store = async_db_summary(store_rows)
        consumed = [r for r in store_rows if r['consumed']]
        if consumed:
            window = (max(r['accepted_at_us'] for r in consumed) - min(r['accepted_at_us'] for r in consumed)) / 1e6
            store['acceptance_window_seconds'] = round(window, 3)
            store['acceptances_per_second'] = round(len(consumed) / window, 3) if window else None
        result |= {'async': store, 'all_final_within_deadline': all_final,
                   'completion_recorded': completion_recorded(management_before, management_after, env.instances)}
        if fault is not None:
            result['expected_confirmed'] = fault.expected_confirmed(env, sale_id, plan, store_rows)
            result['pg_payments'] = final['pg_payments']
        checks = evaluate_async(result, condition, plan, fault)
        if fault is not None:
            checks += fault.checks(env, sale_id, store_rows, result)
    elif fault is not None:
        result['pg_payments'] = final['pg_payments']
        checks = evaluate_v1_fault(result, plan) + fault.checks(env, sale_id, [], result)
    else:
        checks = evaluate(result, condition, plan)
    phases['total_seconds'] = round(time.monotonic() - started, 2)
    return {
        'condition': condition.name, 'experiment': condition.experiment, 'label': None,
        'status': 'passed' if not failures(checks) else 'failed',
        'plan': plan.__dict__,
        'checks': checks, 'failures': failures(checks),
        'k6': k6_summary, 'load_generator': load_generator, 'db': db, 'abuse': abuse_server,
        'cache': cache, 'rate_limit': rate_limit, 'gate': gate, 'gate_updates': gate_updates,
        'derived': derived(k6_summary, db, cache, rate_limit, plan.abusers, gate),
        'verifier_violations': verifier_violations, 'sql_violations': len(violations),
        'order_create_by_instance': order_create_by_instance,
        'load_balancer_access': {'order_create_split': split(access['order_creates']),
                                 'order_payment_split': split(access['order_payments']),
                                 'auth_failures': access['auth_failures']},
        'management': {'instances': instances, 'combined': combine_management(instances)},
        'mysql': mysql, 'durations_seconds': phases, 'started_at': env.started_at, 'run_id': env.run_id,
        'api': condition.api, 'async': store,
        'final_seconds_after_load': phases.get('final_seconds_after_load'),
        'all_final_within_deadline': result.get('all_final_within_deadline'),
        'completion_recorded': result.get('completion_recorded'),
        'fault': fault.record() if fault is not None else None,
    }


def settings_record(condition):
    """Effective switch and limit values; max-requests is passed explicitly and differs from today's default or not."""
    defaults = dict(APPLICATION_DEFAULTS)
    default_max = defaults.pop('order_rate_limit_max_requests')
    overridden = {}
    if condition.rate_limit_max_requests != default_max:
        overridden['order_rate_limit_max_requests'] = {'value': condition.rate_limit_max_requests,
                                                       'application_default': default_max}
    max_requests = condition.rate_limit_max_requests
    return {'sale_cache_enabled': condition.sale_cache, 'order_rate_limit_enabled': condition.order_rate_limit,
            'sold_out_gate_enabled': condition.sold_out_gate, 'sold_out_gate_passed_explicitly': True,
            'order_rate_limit_max_requests': max_requests, 'order_rate_limit_max_requests_passed_explicitly': True,
            'order_rate_limit_window': APPLICATION_DEFAULTS['order_rate_limit_window'],
            'overridden_application_defaults': overridden, 'defaults_not_overridden': defaults}


def sales_extra_env(condition):
    """Settings passed to every sales JVM; max-requests is explicit so old and new conditions stay comparable."""
    return {CACHE_SWITCH: 'true' if condition.sale_cache else 'false',
            GATE_SWITCH: 'true' if condition.sold_out_gate else 'false',
            **order_rate_limit_max_requests_setting(condition.rate_limit_max_requests)}


def run_one(root, condition, plan, index, label, prepare_concurrency):
    """One fresh environment and one measured run; the result is always written, passed or failed."""
    raw = Path(root) / condition.name / 'raw' / f'run-{index}'
    env = Isolated(raw, instances=INSTANCE_NAMES, load_balancer=True, order_rate_limit=condition.order_rate_limit,
                   sales_extra_env=sales_extra_env(condition))
    started = time.monotonic()
    try:
        env.start()
        env_seconds = round(time.monotonic() - started, 2)
        summary = asyncio.run(run_once(env, condition, plan, label, prepare_concurrency))
        summary['durations_seconds']['environment_start_seconds'] = env_seconds
        summary['environment'] = environment_record(env, label) | {
            'settings': settings_record(condition), 'dirty_excluding_results': dirty_outside(root)}
    except Exception as exc:
        summary = {'condition': condition.name, 'status': 'failed', 'failures': [f'{type(exc).__name__}: {exc}'],
                   'error': f'{type(exc).__name__}: {exc}', 'plan': plan.__dict__}
        print(f'FAILED {condition.name} run {index}: {type(exc).__name__}: {exc}', file=sys.stderr)
    finally:
        env.close()
    summary['label'] = label
    summary['run'] = index
    summary['durations_seconds_total_including_cleanup'] = round(time.monotonic() - started, 2)
    return summary


def aggregate_file(condition, plan, label, summaries, order):
    complete = [s for s in summaries if 'k6' in s]
    return {'condition': condition.name, 'experiment': condition.experiment, 'label': label,
            'settings': settings_record(condition), 'plan': plan.__dict__, 'runs': len(summaries),
            'passed': bool(summaries) and all(s['status'] == 'passed' for s in summaries),
            'failures': {f'run-{s["run"]}': s['failures'] for s in summaries if s['failures']},
            'execution_order': order,
            'environment': complete[0]['environment'] if complete else None,
            'metrics': aggregate(complete) if complete else {},
            'median_total_run_seconds': statistics.median(s['durations_seconds']['total_seconds']
                                                          for s in complete) if complete else None}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--conditions', default=','.join(DEFAULT_CONDITIONS),
                        help='comma separated, run in this order every round; default a-off,a-on,b-off,b-on; '
                             'the 2-7 pair is c-off,c-on')
    parser.add_argument('--order-file', default='execution-order.json',
                        help='where the run order is written inside --output; refused if it exists')
    parser.add_argument('--rounds', type=int, default=1, help='runs per condition, interleaved round by round')
    parser.add_argument('--output', required=True, help='results root; one directory per condition is created in it')
    parser.add_argument('--label', default=None, help='free text stored in every result')
    parser.add_argument('--prepare-concurrency', type=int, default=16)
    for name in ('users', 'stock', 'vus', 'detail-views', 'abusers', 'abuser-vus-per-member'):
        parser.add_argument(f'--{name}', type=int, help='smoke-test override of the plan; marks the result unofficial')
    args = parser.parse_args()
    names = args.conditions.split(',')
    unknown = [n for n in names if n not in CONDITIONS]
    if unknown or args.rounds < 1:
        parser.error(f'unknown condition {unknown}' if unknown else '--rounds must be at least 1')
    root = Path(args.output)
    if (root / args.order_file).exists():
        parser.error(f'{root / args.order_file} already exists; use another --order-file')
    for name in names:
        if (root / name / 'aggregate.json').exists():
            parser.error(f'{root / name / "aggregate.json"} already exists; use another --output')
    plans, labels = {}, {}
    for name in names:
        policy = policy_plan(CONDITIONS[name])
        plans[name] = with_overrides(policy, args.users, args.stock, args.vus, args.detail_views, args.abusers,
                                   args.abuser_vus_per_member)
        labels[name] = args.label if plans[name] == policy else ' | '.join(
            filter(None, [args.label, f'UNOFFICIAL override {plans[name]}']))
    summaries = {name: [] for name in names}
    order = []
    for index, name in execution_order(names, args.rounds):
        summary = run_one(root, CONDITIONS[name], plans[name], index, labels[name], args.prepare_concurrency)
        save_dir = root / name
        save_dir.mkdir(parents=True, exist_ok=True)
        save(save_dir / f'run-{index}.json', summary)
        summaries[name].append(summary)
        order.append({'position': len(order) + 1, 'condition': name, 'run': index, 'status': summary['status'],
                      'started_at': summary.get('started_at')})
        save(root / args.order_file, order)
        print(json.dumps({'condition': name, 'run': index, 'status': summary['status'],
                          'failures': summary['failures'],
                          'throughput': summary.get('k6', {}).get('http_requests_per_second')}), flush=True)
    passed = True
    for name in names:
        result = aggregate_file(CONDITIONS[name], plans[name], labels[name], summaries[name],
                                [o for o in order if o['condition'] == name])
        save(root / name / 'aggregate.json', result)
        passed = passed and result['passed']
        print(json.dumps({'condition': name, 'passed': result['passed'], 'failures': result['failures']}))
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
