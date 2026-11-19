#!/usr/bin/env python3
"""Explicit, long-running 1-7-5 experiment suite; any failed run exits nonzero."""
import argparse
import json
from pathlib import Path
import signal
import subprocess
import sys


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    root = Path(args.output).resolve()
    root.mkdir(parents=True, exist_ok=False)
    directory = Path(__file__).resolve().parent
    commands = []
    for users in (100, 1000):
        for run in range(1, 4):
            commands.append((f'oversub-{users}-{run}', 'run_oversubscription.py', ['--users', str(users)]))
        commands.append((f'suite-{users}', 'run_scenarios.py', ['--users', str(users)]))
    commands.extend([('forcekill', 'run_forcekill.py', []), ('outage', 'run_outage.py', [])])
    outcomes = []
    def stop(_signum, _frame):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, stop)
    try:
        for name, script, options in commands:
            argv = [sys.executable, '-B', str(directory / script), *options, '--output', str(root / name)]
            with (root / f'{name}.command.log').open('w') as log:
                child = subprocess.Popen(argv, stdout=log, stderr=subprocess.STDOUT)
                try:
                    code = child.wait()
                except BaseException:
                    child.terminate()
                    child.wait()
                    raise
            outcomes.append({'name': name, 'command': argv, 'exit_code': code})
            (root / 'runs.json').write_text(json.dumps(outcomes, indent=2) + '\n')
        for users in (100, 1000):
            name = f'matrix-{users}'
            argv = [sys.executable, '-B', str(directory / 'audit_matrix.py'), '--users', str(users), '--oversub',
                    *(str(root / f'oversub-{users}-{n}') for n in range(1, 4)), '--suite', str(root / f'suite-{users}'),
                    '--output', str(root / f'{name}.json')]
            with (root / f'{name}.command.log').open('w') as log:
                code = subprocess.run(argv, stdout=log, stderr=subprocess.STDOUT).returncode
            outcomes.append({'name': name, 'command': argv, 'exit_code': code})
            (root / 'runs.json').write_text(json.dumps(outcomes, indent=2) + '\n')
    except KeyboardInterrupt:
        print('interrupted; partial results are incomplete', file=sys.stderr)
        sys.exit(130)
    sys.exit(1 if any(x['exit_code'] != 0 for x in outcomes) else 0)
