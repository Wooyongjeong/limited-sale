"""Detect host suspend during a measured run: wall and monotonic clocks must advance together."""
import math
import time

LIMIT_SECONDS = 5


def sample():
    return {'wall': time.time(), 'monotonic': time.monotonic()}


def drift_seconds(start, end):
    return abs((end['wall'] - start['wall']) - (end['monotonic'] - start['monotonic']))


def record(start, end):
    return {'start': start, 'end': end, 'drift_seconds': drift_seconds(start, end), 'limit_seconds': LIMIT_SECONDS}


def _valid(value):
    return (isinstance(value, dict) and all(isinstance(value.get(k), (int, float)) and not isinstance(value.get(k), bool)
            and math.isfinite(value[k]) for k in ('wall', 'monotonic')))


def clock_errors(start, end):
    if not _valid(start) or not _valid(end):
        return ['clock guard samples missing or malformed']
    drift = drift_seconds(start, end)
    return [f'host clock drift {drift:.2f}s exceeds {LIMIT_SECONDS}s; machine may have been suspended'] if drift > LIMIT_SECONDS else []


def record_errors(value):
    if not isinstance(value, dict):
        return ['clock guard record missing or malformed']
    return clock_errors(value.get('start'), value.get('end'))
