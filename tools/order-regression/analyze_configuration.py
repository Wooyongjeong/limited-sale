#!/usr/bin/env python3
"""Correlate failed simulation setup responses to sanitized PG proxy observations."""
import json
from pathlib import Path
import sys


def main():
    root = Path(sys.argv[1])
    try:
        targets = {row['order_public_id']: row['pg_key'] for row in json.loads((root/'configuration-targets.json').read_text())}
        attempts = json.loads((root/'configuration-attempts.json').read_text())
        recorded = json.loads((root/'proxy-events-at-failure.json').read_text())
        events = recorded['events'] if isinstance(recorded, dict) else recorded
        active_after = recorded.get('active') if isinstance(recorded, dict) else None
        reports = []
        for attempt in attempts:
            if attempt.get('http_status') == 200:
                continue
            key = targets[attempt['order_public_id']]
            matched = [e for e in events if e['path'] == '/api/v1/simulations/' + key and
                       e['started_ns'] <= attempt['finished_ns'] and e['finished_ns'] >= attempt['started_ns']]
            reports.append({'order_public_id': attempt['order_public_id'], 'pg_key': key,
                            'expected_scenario': attempt['scenario'], 'expected_delay_seconds': attempt['delay_seconds'],
                            'http_status': attempt.get('http_status'), 'code': attempt.get('code'),
                            'error': attempt.get('error'), 'elapsed_seconds': (attempt['finished_ns']-attempt['started_ns'])/1e9,
                            'proxy_events': matched})
        print(json.dumps({'active_after_drain': active_after, 'failed': reports}, indent=2))
        return 0
    except (OSError, ValueError, KeyError, IndexError, TypeError) as exc:
        print(f'invalid evidence: {exc}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
