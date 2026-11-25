#!/usr/bin/env python3
"""Stage 3-8 fault runs of the v2 path: broker outage, a killed instance, duplicate delivery and the sale ending while
requests wait, each once under load (3-8 #3, #8).

Each run starts a fresh isolated environment like the comparison runs (two sales instances behind Caddy, Mock PG, own
MySQL, Redis and broker) and reuses run_comparison.run_once for the v2-async condition on a smaller profile (2,000 users,
300 units, 200 VUs). A fault object injects its failure while k6 runs or after every acceptance is final, and adds its
own checks to the v2 pass criteria.
"""
import argparse
import asyncio
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import threading
import time

from comparison import CONDITIONS, Plan, failures
from isolated import Isolated, command
from load_metrics import parse_prometheus
from run_balanced import INSTANCE_NAMES
from run_baseline import environment_record, scrape
from run_comparison import dirty_outside, run_once, sales_extra_env, settings_record, unfinished_acceptances
from run_oversubscription import save

PLAN = Plan(users=2_000, stock=300, vus=200, detail_views=0, abusers=0)
CONDITION = CONDITIONS['v2-async']


def now():
    return datetime.now(timezone.utc).isoformat()


def sql_value(env, statement):
    return env.mysql('sales', 'USE limited_sale;\n' + statement).strip()


class Fault:
    name = 'none'

    def __init__(self):
        self.events = []
        self.error = None
        self.thread = None

    def note(self, event, **details):
        self.events.append({'at': now(), 'event': event, **details})

    def start(self, env, sale_id):
        self.thread = threading.Thread(target=self._guarded, args=(env, sale_id), daemon=True)
        self.thread.start()

    def _guarded(self, env, sale_id):
        try:
            self.during(env, sale_id)
        except Exception as exc:  # the run must still finish and report the failure
            self.error = f'{type(exc).__name__}: {exc}'
            self.note('error', detail=self.error)

    def during(self, env, sale_id):
        pass

    def after_final(self, env, sale_id):
        pass

    def join(self):
        if self.thread is not None:
            self.thread.join(timeout=300)

    def expected_confirmed(self, env, sale_id, plan, rows):
        return plan.expected_confirmed

    def checks(self, env, sale_id, rows, result):
        found = [check('fault_injected_without_error', None, self.error)]
        return found + self.own_checks(env, sale_id, rows, result)

    def own_checks(self, env, sale_id, rows, result):
        return []

    def record(self):
        return {'name': self.name, 'events': self.events, 'error': self.error}


def check(name, expected, actual, passed=None):
    return {'name': name, 'passed': expected == actual if passed is None else passed, 'expected': expected,
            'actual': actual}


def broker(env):
    return env.containers[3]


def acceptance_count(env, sale_id):
    return int(sql_value(env, f'SELECT COUNT(*) FROM order_acceptances WHERE sale_id={sale_id};'))


def wait_until(predicate, timeout=60, interval=0.05):
    """Poll until predicate() is true; returns whether it became true in time. At this load the consumer sells the
    stock out within about a second, so faults trigger on what the database shows, not after a fixed delay."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(interval)
    return False


class BrokerOutage(Fault):
    """① The broker stops as soon as the first acceptance is stored, for 20 s; acceptance goes on, publishing and
    consuming wait."""
    name = 'broker-outage'

    def during(self, env, sale_id):
        wait_until(lambda: acceptance_count(env, sale_id) > 0)
        command('docker', 'stop', broker(env))
        self.note('broker stopped', unfinished=unfinished_acceptances(env, sale_id))
        time.sleep(20)
        command('docker', 'start', broker(env))
        env.wait_kafka()
        self.note('broker started', unfinished=unfinished_acceptances(env, sale_id))

    def own_checks(self, env, sale_id, rows, result):
        stopped = [e for e in self.events if e['event'] == 'broker stopped']
        return [check('requests_were_waiting_when_the_broker_stopped', True,
                      bool(stopped) and stopped[0]['unfinished'] > 0)]


class InstanceKill(Fault):
    """② One sales JVM (publisher, consumer and API) is killed with SIGKILL while acceptances wait, restarted 10 s
    later."""
    name = 'instance-kill'

    def during(self, env, sale_id):
        wait_until(lambda: unfinished_acceptances(env, sale_id) >= 20)
        # env.processes: Mock PG first, then the sales instances in order.
        victim = env.processes[1 + env.instances.index('sales2')]
        os.killpg(victim.pid, signal.SIGKILL)
        victim.wait(timeout=30)
        self.killed_exit = victim.returncode
        self.note('sales2 killed', exit=victim.returncode, unfinished=unfinished_acceptances(env, sale_id))
        time.sleep(10)
        restarted = env.start_sales('sales2', log_name='sales2-restarted')
        env.health(env.ports['sales2'], restarted)
        self.note('sales2 restarted', unfinished=unfinished_acceptances(env, sale_id))

    def own_checks(self, env, sale_id, rows, result):
        killed = [e for e in self.events if e['event'] == 'sales2 killed']
        return [check('instance_died_by_sigkill', -signal.SIGKILL, killed[0]['exit'] if killed else None),
                check('requests_were_waiting_when_it_was_killed', True,
                      bool(killed) and killed[0]['unfinished'] > 0)]


def duplicate_count(env):
    total = 0.0
    for name in env.instances:
        parsed = parse_prometheus(scrape(env.ports[name + '_management'])[0])
        total += sum(value for (series, pairs), value in parsed.items()
                     if series == 'order_acceptance_processed_total' and dict(pairs).get('result') == 'duplicate')
    return total


class DuplicateDelivery(Fault):
    """③ After every acceptance is final, each one's latest outbox row is inserted once more and so sent again."""
    name = 'duplicate-delivery'

    def after_final(self, env, sale_id):
        before = duplicate_count(env)
        orders_before = int(sql_value(env, f'SELECT COUNT(*) FROM purchase_orders WHERE sale_id={sale_id};'))
        inserted = int(sql_value(env, f"""
            INSERT INTO order_outbox(topic, message_key, payload, created_at, attempts, acceptance_id)
            SELECT o.topic, o.message_key, o.payload, UTC_TIMESTAMP(6), 0, o.acceptance_id FROM order_outbox o
            JOIN order_acceptances a ON a.id = o.acceptance_id
            WHERE a.sale_id = {sale_id}
              AND o.id = (SELECT MAX(l.id) FROM order_outbox l WHERE l.acceptance_id = o.acceptance_id);
            SELECT ROW_COUNT();"""))
        self.note('outbox rows copied', rows=inserted, orders=orders_before)
        deadline = time.monotonic() + 180
        processed = 0
        while time.monotonic() < deadline:
            processed = duplicate_count(env) - before
            if processed >= inserted:
                break
            time.sleep(1)
        self.inserted, self.processed = inserted, int(processed)
        self.orders_before = orders_before
        self.orders_after = int(sql_value(env, f'SELECT COUNT(*) FROM purchase_orders WHERE sale_id={sale_id};'))
        self.note('duplicates consumed', duplicates=self.processed, orders=self.orders_after)

    def own_checks(self, env, sale_id, rows, result):
        inserted = getattr(self, 'inserted', None)
        return [check('every_acceptance_was_sent_again', True, bool(inserted) and inserted == result['async']['consumed']),
                check('every_copy_was_consumed_as_a_duplicate', inserted, getattr(self, 'processed', None)),
                check('no_order_added_by_the_copies', getattr(self, 'orders_before', None),
                      getattr(self, 'orders_after', None))]


class EndDuringBacklog(Fault):
    """④ The broker stops as soon as the first acceptance is stored; once every VU's request is accepted and waiting
    the sale's end is set to now, and the broker comes back 8 s later, so requests accepted before the end are
    processed after it and the users who come later are turned away for the period."""
    name = 'end-during-backlog'

    def during(self, env, sale_id):
        wait_until(lambda: acceptance_count(env, sale_id) > 0)
        command('docker', 'stop', broker(env))
        self.note('broker stopped')
        # Each user polls inside its iteration, so at most VUS requests can be waiting: end the sale once they all are.
        wait_until(lambda: acceptance_count(env, sale_id) >= PLAN.vus, interval=0.2)
        # The test stands in for the clock: the end time arrives now, while the accepted requests wait.
        self.ends_at_us = int(sql_value(env, f"""
            UPDATE sales SET ends_at = UTC_TIMESTAMP(6) WHERE id = {sale_id};
            SELECT TIMESTAMPDIFF(MICROSECOND, '2000-01-01', ends_at) FROM sales WHERE id = {sale_id};"""))
        self.note('sale ended', unfinished=unfinished_acceptances(env, sale_id))
        time.sleep(8)
        command('docker', 'start', broker(env))
        env.wait_kafka()
        self.note('broker started')

    def expected_confirmed(self, env, sale_id, plan, rows):
        # Every request accepted before the end is served; the stock or that number, whichever is smaller, sells.
        return min(plan.stock, sum(1 for r in rows if r['consumed']))

    def own_checks(self, env, sale_id, rows, result):
        end = getattr(self, 'ends_at_us', None)
        if end is None:
            return [check('sale_end_was_set', True, False)]
        consumed = [r for r in rows if r['consumed']]
        late = [r for r in rows if not r['consumed'] and r['accepted_at_us'] >= end]
        return [
            check('only_requests_before_the_end_were_accepted', True, all(r['accepted_at_us'] < end for r in consumed)),
            check('no_accepted_request_rejected_for_the_period', 0,
                  sum(1 for r in consumed if r.get('rejection_reason') == 'SALE_OUTSIDE_PERIOD')),
            check('orders_were_made_after_the_end', True,
                  any(r['status'] == 'ORDER_CREATED' and r['finished_at_us'] > end for r in consumed)),
            check('requests_after_the_end_were_turned_away', True,
                  bool(late) and all(r['rejection_reason'] in ('SALE_OUTSIDE_PERIOD', 'SALE_SOLD_OUT') for r in late)
                  and any(r['rejection_reason'] == 'SALE_OUTSIDE_PERIOD' for r in late)),
        ]


FAULTS = {cls.name: cls for cls in (BrokerOutage, InstanceKill, DuplicateDelivery, EndDuringBacklog)}


def run_fault(root, fault_class, label, prepare_concurrency):
    raw = Path(root) / 'raw' / fault_class.name
    env = Isolated(raw, instances=INSTANCE_NAMES, load_balancer=True, order_rate_limit=CONDITION.order_rate_limit,
                   sales_extra_env=sales_extra_env(CONDITION))
    started = time.monotonic()
    fault = fault_class()
    try:
        env.start()
        summary = asyncio.run(run_once(env, CONDITION, PLAN, label, prepare_concurrency, fault))
        summary['environment'] = environment_record(env, label) | {
            'settings': settings_record(CONDITION), 'dirty_excluding_results': dirty_outside(root)}
    except Exception as exc:
        summary = {'condition': CONDITION.name, 'status': 'failed', 'failures': [f'{type(exc).__name__}: {exc}'],
                   'error': f'{type(exc).__name__}: {exc}', 'fault': fault.record()}
        print(f'FAILED {fault_class.name}: {type(exc).__name__}: {exc}', file=sys.stderr)
    finally:
        env.close()
    summary['label'] = label
    summary['plan'] = PLAN.__dict__
    summary['durations_seconds_total_including_cleanup'] = round(time.monotonic() - started, 2)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--faults', default=','.join(FAULTS), help='comma separated, in this order')
    parser.add_argument('--output', required=True, help='results root; one <fault>.json per fault is written in it')
    parser.add_argument('--label', default=None)
    parser.add_argument('--prepare-concurrency', type=int, default=16)
    for name in ('users', 'stock', 'vus'):
        parser.add_argument(f'--{name}', type=int, help='smoke-test override of the plan; marks the result unofficial')
    args = parser.parse_args()
    global PLAN
    official = PLAN
    PLAN = Plan(users=args.users or PLAN.users, stock=args.stock or PLAN.stock, vus=args.vus or PLAN.vus,
                detail_views=0, abusers=0)
    label = args.label if PLAN == official else ' | '.join(filter(None, [args.label, f'UNOFFICIAL override {PLAN}']))
    names = args.faults.split(',')
    unknown = [n for n in names if n not in FAULTS]
    if unknown:
        parser.error(f'unknown fault {unknown}')
    root = Path(args.output)
    root.mkdir(parents=True, exist_ok=True)
    for name in names:
        if (root / f'{name}.json').exists():
            parser.error(f'{root / name}.json already exists; use another --output')
    passed = True
    for name in names:
        summary = run_fault(root, FAULTS[name], label, args.prepare_concurrency)
        save(root / f'{name}.json', summary)
        passed = passed and summary['status'] == 'passed'
        print(json.dumps({'fault': name, 'status': summary['status'], 'failures': summary['failures']}), flush=True)
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
