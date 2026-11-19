#!/usr/bin/env python3
"""Audit recorded DB samples for review warning cadence and reservation retention."""
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

from clock_guard import record_errors
from verify import verify


def audit(samples):
    errors = []
    if not samples:
        return ['missing samples']
    if samples[0]['elapsed_seconds'] > 30 or samples[-1]['elapsed_seconds'] < 630:
        errors.append('outage sample coverage is incomplete')
    if any(b['elapsed_seconds'] - a['elapsed_seconds'] > 30 or b['elapsed_seconds'] <= a['elapsed_seconds']
           for a, b in zip(samples, samples[1:])):
        errors.append('sample gap exceeds 30 seconds')
    if any(s['row']['status'] != 'PAYMENT_PENDING' or s['row']['reservation'] != 'RESERVED'
           or s['row']['reserved_quantity'] != 1 for s in samples):
        errors.append('pending reservation not retained')
    warning_times = sorted({datetime.fromisoformat(s['row']['next_review_warning_at']) for s in samples
                            if s['row']['next_review_warning_at'] is not None})
    expires = datetime.fromisoformat(samples[0]['row']['expires_at'])
    if len(warning_times) < 2 or (warning_times[0]-expires).total_seconds() < 300:
        errors.append('first review schedule missing or early')
    if len(warning_times) >= 2 and (warning_times[1]-warning_times[0]).total_seconds() < 300:
        errors.append('repeated review warning scheduled early')
    checks = []
    for sample in samples:
        value = sample['row']['last_checked_at']
        if value is not None:
            checked = datetime.fromisoformat(value)
            if not checks or checks[-1] != checked:
                checks.append(checked)
    if len(checks) < 2:
        errors.append('retry observations missing')
    for before, after in zip(checks, checks[1:]):
        if (after - before).total_seconds() < 10 and not before < expires <= after:
            errors.append('retry interval shorter than ten seconds outside expiry transition')
    if not any(s['row']['requires_review'] for s in samples):
        errors.append('requires_review absent')
    return errors


def audit_logs(warnings, expires_at):
    if len(warnings) < 2:
        return ['warning logs missing']
    times = sorted(datetime.fromisoformat(line.split()[0]) for line in warnings)
    expiry = datetime.fromisoformat(expires_at).replace(tzinfo=timezone.utc)
    if times[0] < expiry or (times[1] - times[0]).total_seconds() < 300:
        return ['warning log timing is early']
    return []


def audit_recovery(summary, snapshot):
    elapsed = summary['terminal_elapsed_seconds']
    errors = [] if elapsed is not None and 0 <= elapsed <= 120 else ['missing or late convergence']
    return errors + verify(snapshot)


def audit_clock(directory):
    return record_errors(json.loads((directory / 'clock-guard.json').read_text()))


def main():
    directory = Path(sys.argv[1])
    try:
        samples = json.loads((directory / 'outage-samples.json').read_text())
        summary = json.loads((directory / 'outage-summary.json').read_text())
        snapshot = json.loads((directory / 'snapshot.json').read_text())
        errors = audit(samples) + audit_recovery(summary, snapshot) + audit_clock(directory)
        if summary['downtime_seconds'] < 660:
            errors.append('wall time below 660 seconds')
        errors.extend(audit_logs(summary['warnings'], samples[0]['row']['expires_at']))
    except (OSError, ValueError, KeyError, IndexError, TypeError) as exc:
        print(f'invalid evidence: {exc}', file=sys.stderr)
        return 2
    for error in errors:
        print(error, file=sys.stderr)
    print(f'{len(errors)} outage evidence failure(s)')
    return bool(errors)


if __name__ == '__main__':
    sys.exit(main())
