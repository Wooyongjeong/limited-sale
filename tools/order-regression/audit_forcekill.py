#!/usr/bin/env python3
"""Audit recorded force-kill boundary and recovery artifacts."""
from datetime import datetime
import json
from pathlib import Path
import sys
from clock_guard import record_errors
from verify import verify


def audit_boundary(kind, boundary, execution, final):
    errors = []
    state = boundary['state']
    if boundary['profile_users'] != 100 or boundary['profile_stock'] != 30:
        errors.append('unexpected force-kill profile')
    if not state['lease_held'] or state['order_status'] != 'PAYMENT_PENDING':
        errors.append('missing committed lease or pending order')
    if datetime.fromisoformat(execution['kill_at']) < datetime.fromisoformat(boundary['at']):
        errors.append('process termination predates boundary observation')
    if execution['pid'] != boundary['sales_pid'] or execution['exit_code'] != -9:
        errors.append('target process was not killed with SIGKILL')
    if 'error' not in execution['payment_request']:
        errors.append('payment response arrived before process termination')
    elapsed = execution['terminal_elapsed_seconds']
    if elapsed is None or not 0 <= elapsed <= 120:
        errors.append('missing or late convergence')
    proxy = boundary['proxy'] or {}
    pg = boundary['pg']
    if kind == 'lease':
        if pg or proxy.get('stage') != 'before_forward':
            errors.append('lease boundary not observed before forwarding')
    elif kind == 'pg_success':
        if (len(pg) != 1 or pg[0]['status'] != 'SUCCEEDED' or state['payment_status'] == 'SUCCEEDED'
                or proxy.get('stage') != 'after_upstream' or proxy.get('http_status') != 200):
            errors.append('PG response was not held after success')
    elif kind == 'payment_persisted':
        if (len(pg) != 1 or pg[0]['status'] != 'SUCCEEDED' or state['payment_status'] != 'SUCCEEDED'
                or not boundary['sale_lock_connection'] or boundary['data_lock_waits'] < 1):
            errors.append('persisted payment and blocked order finish not observed')
    else:
        errors.append('unknown boundary')
    if len(final['payments']) != 1 or final['payments'][0]['pg_key'] != state['pg_key']:
        errors.append('PG key changed across kill')
    if len(final['pg_payments']) != 1 or final['pg_payments'][0]['pg_key'] != state['pg_key']:
        errors.append('PG record does not match observed key')
    return errors


def audit_clock(case):
    return record_errors(json.loads((case / 'clock-guard.json').read_text()))


def main():
    root = Path(sys.argv[1])
    failures = []
    try:
        for kind in ('lease', 'pg_success', 'payment_persisted'):
            for n in range(1, 4):
                case = root / f'{kind}-{n}'
                boundary = json.loads((case / 'boundary.json').read_text())
                execution = json.loads((case / 'execution.json').read_text())
                final = json.loads((case / 'snapshot.json').read_text())
                failures.extend(f'{case.name}: {error}' for error in audit_boundary(kind, boundary, execution, final) + verify(final) + audit_clock(case))
    except (OSError, ValueError, KeyError, IndexError, TypeError) as exc:
        print(f'invalid evidence: {exc}', file=sys.stderr)
        return 2
    for failure in failures:
        print(failure, file=sys.stderr)
    print(f'{len(failures)} force-kill evidence failure(s)')
    return bool(failures)


if __name__ == '__main__':
    sys.exit(main())
