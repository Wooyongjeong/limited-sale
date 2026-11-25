#!/usr/bin/env python3
"""Stage 4-3 fault runs: nine scenarios on the whole setup (two sales instances behind Caddy, Mock PG, own MySQL, Redis
and broker), v2 for all of them and v1 where Redis or the database is involved, each three times (4-1 #1 to #3).

Every run is a fresh isolated environment driven by run_comparison.run_once on the small profile (2,000 users,
300 units, 200 VUs) with one fault object. Faults trigger on what the database shows, not after a fixed delay, because
at this load the stock sells out within about a second (3-8). The expected results and pass criteria are fixed in
docs/stages/04-reliability.md ("4-3 장애 실험") before the runs.
"""
import argparse
import asyncio
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import signal
import sys
import threading
import time

from comparison import CONDITIONS, Plan, failures
from isolated import Isolated, command
from load_metrics import parse_prometheus
from run_async_faults import (BrokerOutage, DuplicateDelivery, Fault, check, sql_value, unfinished_acceptances,
                              wait_until)
from run_balanced import INSTANCE_NAMES
from run_baseline import environment_record, scrape
from run_comparison import dirty_outside, run_once, sales_extra_env, settings_record
from run_oversubscription import save

PLAN = Plan(users=2_000, stock=300, vus=200, detail_views=0, abusers=0)
OUTAGE_SECONDS = 20


def stored_requests(env, sale_id, api):
    """Requests of the sale the database has stored: acceptances for v2, request results for v1."""
    table = 'order_acceptances' if api == 'v2' else 'order_request_results'
    return int(sql_value(env, f'SELECT COUNT(*) FROM {table} WHERE sale_id={sale_id};'))


def processed(env, name):
    """Acceptances the instance's consumer has processed so far (all results), or None when it does not answer."""
    try:
        parsed = parse_prometheus(scrape(env.ports[name + '_management'])[0])
    except OSError:
        return None
    return sum(value for (series, _), value in parsed.items() if series == 'order_acceptance_processed_total')


def process_of(env, name):
    # env.processes: Mock PG first, then the sales instances in order; restarts are appended later.
    return env.processes[1 + env.instances.index(name)]


class Scenario(Fault):
    """A fault with the stage 4 checks every run shares: no double charge, and the PG agrees with the orders."""
    paths = ('v2',)

    def __init__(self, api):
        super().__init__()
        self.api = api

    def triggered(self, env, sale_id):
        return wait_until(lambda: stored_requests(env, sale_id, self.api) > 0)

    def checks(self, env, sale_id, rows, result):
        found = super().checks(env, sale_id, rows, result)
        payments = result.get('pg_payments', [])
        succeeded = sum(1 for p in payments if p['status'] == 'SUCCEEDED')
        keys = [p['pg_key'] for p in payments]
        found.append(check('pg_charged_each_order_once', result['db']['confirmed'], succeeded))
        found.append(check('pg_has_one_record_per_payment_key', len(keys), len(set(keys))))
        if result['k6'].get('broken_2xx') is not None:
            found.append(check('no_broken_2xx_responses', 0, result['k6']['broken_2xx']))
        return found


def container_outage(fault, env, names_and_ids, seconds=OUTAGE_SECONDS):
    for name, cid in names_and_ids:
        command('docker', 'stop', cid)
        fault.note(f'{name} stopped')
    time.sleep(seconds)
    for name, cid in names_and_ids:
        command('docker', 'start', cid)
        fault.note(f'{name} started')
    for name, _ in names_and_ids:
        if name == 'kafka':
            env.wait_kafka()
        if name == 'mysql':
            env.wait_mysql('sales')
    fault.note('outage over')


class RedisOutage(Scenario):
    name = 'S1-redis'
    paths = ('v1', 'v2')

    def during(self, env, sale_id):
        self.triggered(env, sale_id)
        container_outage(self, env, [('redis', env.containers[2])])

    def own_checks(self, env, sale_id, rows, result):
        # 2-5 finding 2: how many requests the load balancer answered 502, per endpoint.
        self.bad_gateway = {name: e['status_counts'].get('502', 0) for name, e in result['k6']['endpoints'].items()
                            if e['status_counts'].get('502')}
        self.note('502 by endpoint', counts=self.bad_gateway)
        return [check('redis_was_stopped_and_started', True,
                      any(e['event'] == 'outage over' for e in self.events))]


class KafkaOutage(BrokerOutage, Scenario):
    name = 'S2-kafka'

    def __init__(self, api):
        Scenario.__init__(self, api)

    def during(self, env, sale_id):
        self.triggered(env, sale_id)
        command('docker', 'stop', env.containers[3])
        self.note('broker stopped', unfinished=unfinished_acceptances(env, sale_id))
        time.sleep(OUTAGE_SECONDS)
        command('docker', 'start', env.containers[3])
        env.wait_kafka()
        self.note('broker started', unfinished=unfinished_acceptances(env, sale_id))


class ConsumerKill(Scenario):
    """Kills the instance whose consumer processes the sale (its single partition), so the kill does matter."""
    name = 'S3-kill-restart'
    restart = True

    def during(self, env, sale_id):
        owner = [None]

        def owner_found():
            if unfinished_acceptances(env, sale_id) < 20:
                return False
            counts = {name: processed(env, name) or 0 for name in env.instances}
            busy = [name for name, count in counts.items() if count > 0]
            owner[0] = busy[0] if len(busy) == 1 else None
            return owner[0] is not None
        wait_until(owner_found, timeout=60, interval=0.1)
        victim_name = owner[0] or 'sales2'
        survivor = next(name for name in env.instances if name != victim_name)
        baseline = processed(env, survivor) or 0
        victim = process_of(env, victim_name)
        os.killpg(victim.pid, signal.SIGKILL)
        victim.wait(timeout=30)
        killed_at = time.monotonic()
        self.note('killed', instance=victim_name, exit=victim.returncode, owner_known=owner[0] is not None,
                  unfinished=unfinished_acceptances(env, sale_id))
        if self.restart:
            time.sleep(10)
            restarted = env.start_sales(victim_name, log_name=f'{victim_name}-restarted')
            env.health(env.ports[victim_name], restarted)
            self.note('restarted', instance=victim_name)
        else:
            # Hand-over: the surviving instance processes its first message after the kill.
            took_over = wait_until(lambda: (processed(env, survivor) or 0) > baseline, timeout=120, interval=0.2)
            self.handover_seconds = round(time.monotonic() - killed_at, 1) if took_over else None
            self.note('survivor took over', instance=survivor, seconds=self.handover_seconds)

    def own_checks(self, env, sale_id, rows, result):
        killed = [e for e in self.events if e['event'] == 'killed']
        found = [check('instance_died_by_sigkill', -signal.SIGKILL, killed[0]['exit'] if killed else None),
                 check('killed_the_instance_that_owned_the_partition', True,
                       bool(killed) and killed[0]['owner_known'])]
        if not self.restart:
            seconds = getattr(self, 'handover_seconds', None)
            found.append(check('handover_within_15_seconds', True, seconds is not None and seconds <= 15))
        return found


class ConsumerKillNoRestart(ConsumerKill):
    name = 'S4-kill-no-restart'
    restart = False


class ReadOnlyDatabase(Scenario):
    name = 'S5-read-only'
    paths = ('v1', 'v2')

    def during(self, env, sale_id):
        self.triggered(env, sale_id)
        env.mysql('sales', 'SET GLOBAL super_read_only = ON;')
        self.note('read only')
        time.sleep(OUTAGE_SECONDS)
        env.mysql('sales', 'SET GLOBAL super_read_only = OFF; SET GLOBAL read_only = OFF;')
        self.note('writable')

    def own_checks(self, env, sale_id, rows, result):
        found = [check('database_was_read_only_and_back', ['read only', 'writable'],
                       [e['event'] for e in self.events if e['event'] in ('read only', 'writable')])]
        if self.api == 'v2':
            found.append(check('no_processing_failures_from_the_write_failure', 0,
                               sum(1 for r in rows if r['status'] == 'PROCESSING_FAILED')))
        return found


class MysqlStop(Scenario):
    name = 'S6-mysql-stop'
    paths = ('v1', 'v2')

    def during(self, env, sale_id):
        self.triggered(env, sale_id)
        container_outage(self, env, [('mysql', env.containers[0])])

    def own_checks(self, env, sale_id, rows, result):
        found = [check('mysql_was_stopped_and_started', True, any(e['event'] == 'outage over' for e in self.events))]
        if self.api == 'v2':
            found.append(check('no_processing_failures_from_the_outage', 0,
                               sum(1 for r in rows if r['status'] == 'PROCESSING_FAILED')))
        return found


class ResponseLoss(Scenario):
    """Sets RESPONSE_LOSS (5 s) for every new order's payment key in the Mock PG, as soon as the order exists."""
    name = 'S7-response-loss'

    def __init__(self, api):
        super().__init__(api)
        self.stop = threading.Event()
        self.marked = 0

    def during(self, env, sale_id):
        while not self.stop.is_set():
            keys = sql_value(env, f"SELECT HEX(p.pg_key) FROM payments p JOIN purchase_orders o ON o.id = p.order_id "
                                  f"WHERE o.sale_id = {sale_id};").split()
            if keys:
                values = ','.join(f"(UNHEX('{key}'), 'RESPONSE_LOSS', 5)" for key in keys)
                env.mysql('pg', f'USE mock_pg; INSERT IGNORE INTO mock_scenario_settings(pg_key, scenario, '
                                f'delay_seconds) VALUES {values};')
            time.sleep(0.1)

    def after_final(self, env, sale_id):
        self.stop.set()

    def own_checks(self, env, sale_id, rows, result):
        payments = result.get('pg_payments', [])
        lossy = sum(1 for p in payments if p.get('scenario') == 'RESPONSE_LOSS')
        self.note('payments with RESPONSE_LOSS', count=lossy, of=len(payments))
        return [check('at_least_half_the_payments_lost_their_response', True,
                      bool(payments) and lossy * 2 >= len(payments))]


class RedisAndKafkaOutage(Scenario):
    name = 'S8-redis-and-kafka'

    def during(self, env, sale_id):
        self.triggered(env, sale_id)
        container_outage(self, env, [('redis', env.containers[2]), ('kafka', env.containers[3])])

    def own_checks(self, env, sale_id, rows, result):
        return [check('both_were_stopped_and_started', True, any(e['event'] == 'outage over' for e in self.events))]


class RollingRestart(Scenario):
    name = 'S9-rolling-restart'

    def during(self, env, sale_id):
        self.triggered(env, sale_id)
        self.exits = {}
        for name in env.instances:
            process = process_of(env, name) if name not in self.exits else None
            os.killpg(process.pid, signal.SIGTERM)
            process.wait(timeout=60)
            self.exits[name] = process.returncode
            self.note('stopped', instance=name, exit=process.returncode)
            restarted = env.start_sales(name, log_name=f'{name}-restarted')
            env.health(env.ports[name], restarted)
            self.note('restarted', instance=name)

    def own_checks(self, env, sale_id, rows, result):
        exits = getattr(self, 'exits', {})
        return [check('both_instances_stopped_gracefully', True,
                      len(exits) == 2 and all(code in (0, 143, -signal.SIGTERM) for code in exits.values()))]


SCENARIOS = {cls.name: cls for cls in (RedisOutage, KafkaOutage, ConsumerKill, ConsumerKillNoRestart,
                                       ReadOnlyDatabase, MysqlStop, ResponseLoss, RedisAndKafkaOutage, RollingRestart)}


def runs(names):
    return [(name, path) for name in names for path in SCENARIOS[name].paths]


def run_one(root, name, api, index, label, prepare_concurrency, plan):
    condition = CONDITIONS['v2-async' if api == 'v2' else 'v1-sync']
    raw = Path(root) / 'raw' / f'{name}-{api}' / f'run-{index}'
    env = Isolated(raw, instances=INSTANCE_NAMES, load_balancer=True, order_rate_limit=condition.order_rate_limit,
                   sales_extra_env=sales_extra_env(condition), fixed_redis_port=True, fixed_mysql_port=True)
    fault = SCENARIOS[name](api)
    started = time.monotonic()
    try:
        env.start()
        summary = asyncio.run(run_once(env, condition, plan, label, prepare_concurrency, fault))
        summary['environment'] = environment_record(env, label) | {
            'settings': settings_record(condition), 'dirty_excluding_results': dirty_outside(root)}
    except Exception as exc:
        summary = {'condition': condition.name, 'status': 'failed', 'failures': [f'{type(exc).__name__}: {exc}'],
                   'error': f'{type(exc).__name__}: {exc}', 'fault': fault.record()}
        print(f'FAILED {name} {api} run {index}: {type(exc).__name__}: {exc}', file=sys.stderr)
    finally:
        env.close()
    summary.update({'scenario': name, 'path': api, 'run': index, 'label': label, 'plan': plan.__dict__,
                    'durations_seconds_total_including_cleanup': round(time.monotonic() - started, 2)})
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenarios', default=','.join(SCENARIOS), help='comma separated scenario names')
    parser.add_argument('--rounds', type=int, default=3, help='runs per scenario and path, round by round')
    parser.add_argument('--output', required=True)
    parser.add_argument('--label', default=None)
    parser.add_argument('--prepare-concurrency', type=int, default=16)
    for option in ('users', 'stock', 'vus'):
        parser.add_argument(f'--{option}', type=int, help='smoke-test override of the plan; marks the result unofficial')
    args = parser.parse_args()
    names = args.scenarios.split(',')
    unknown = [n for n in names if n not in SCENARIOS]
    if unknown:
        parser.error(f'unknown scenario {unknown}')
    plan = Plan(users=args.users or PLAN.users, stock=args.stock or PLAN.stock, vus=args.vus or PLAN.vus,
                detail_views=0, abusers=0)
    label = args.label if plan == PLAN else ' | '.join(filter(None, [args.label, f'UNOFFICIAL override {plan}']))
    root = Path(args.output)
    root.mkdir(parents=True, exist_ok=True)
    order_file = root / 'execution-order.json'
    if order_file.exists():
        parser.error(f'{order_file} already exists; use another --output')
    order, passed = [], True
    for index in range(1, args.rounds + 1):
        for name, api in runs(names):
            summary = run_one(root, name, api, index, label, args.prepare_concurrency, plan)
            target = root / f'{name}-{api}'
            target.mkdir(parents=True, exist_ok=True)
            save(target / f'run-{index}.json', summary)
            passed = passed and summary['status'] == 'passed'
            order.append({'position': len(order) + 1, 'scenario': name, 'path': api, 'run': index,
                          'status': summary['status'], 'failures': summary['failures']})
            save(order_file, order)
            print(json.dumps({'scenario': name, 'path': api, 'run': index, 'status': summary['status'],
                              'failures': summary['failures']}), flush=True)
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
