#!/usr/bin/env python3
"""Audit three full-scope runs per general scenario for one user profile."""
import argparse
from collections import Counter
import json
from pathlib import Path
import sys

from audit_case import audit_case


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--users', type=int, choices=(100, 1000), required=True)
    parser.add_argument('--oversub', nargs=3, required=True)
    parser.add_argument('--suite', required=True)
    parser.add_argument('--mixed', help='optional suite directory containing stronger mixed evidence')
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    jobs = [('oversubscription', i+1, Path(path)) for i, path in enumerate(args.oversub)]
    for name in ('replay_conflict', 'same_user', 'undersubscribed', 'decline_reuse', 'mixed'):
        root = Path(args.mixed) if name == 'mixed' and args.mixed else Path(args.suite)
        jobs.extend((name, n, root / f'{name}-{n}') for n in range(1, 4))
    result = []
    seen_sales = set()
    for scenario, iteration, path in jobs:
        try:
            document = json.loads((path / 'snapshot.json').read_text())
            errors = audit_case(path)
            requests = document['requests']
            planned = document['manifest']['attempts']
            counts = {'planned': len(planned), 'sent': sum(r['sent'] is True for r in requests),
                      'results': dict(Counter(r['result'] for r in requests)),
                      'rejections': dict(Counter(r['rejection_reason'] for r in requests if r['result'] == 'REJECTED')),
                      'errors': sum(r['result'] == 'ERROR' for r in requests),
                      'missing': len({p['attempt_id'] for p in planned} - {r['attempt_id'] for r in requests}),
                      'orders': dict(Counter(o['status'] for o in document['orders']))}
            resource_path = path / 'resources.json' if scenario == 'oversubscription' else path.parent / 'resources.json'
            resources = json.loads(resource_path.read_text())
            sale_identity = (resources['run_id'], document['manifest']['sales'][0]['id'])
            if sale_identity in seen_sales:
                errors.append('event reused across runs')
            seen_sales.add(sale_identity)
            expected_stock = 30 if args.users == 100 else 300
            if document['manifest']['sales'][0]['total_quantity'] != expected_stock:
                errors.append('incorrect profile stock')
            raw = json.loads((path / 'order-attempts.json').read_text())
            batches = raw if isinstance(raw, list) else [raw]
            barriers = [b['barrier_arrived'] for b in batches]
            expected_burst = (20 if args.users == 100 else 200) if scenario == 'undersubscribed' else args.users
            if expected_burst not in barriers:
                errors.append('profile burst did not reach the required barrier')
            result.append({'scenario': scenario, 'run': iteration, 'path': str(path),
                           'counts': counts, 'barriers': barriers, 'failures': errors})
        except (OSError, ValueError, KeyError, TypeError) as exc:
            result.append({'scenario': scenario, 'run': iteration, 'path': str(path),
                           'failures': [f'{type(exc).__name__}: {exc}']})
    failed = sum(bool(r['failures']) for r in result)
    Path(args.output).write_text(json.dumps({'users': args.users, 'cases': result, 'failed': failed}, indent=2) + '\n')
    print(f'{len(result)} case(s), {failed} failed')
    sys.exit(bool(failed))
