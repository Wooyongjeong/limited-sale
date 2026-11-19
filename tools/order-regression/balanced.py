"""Pure logic for the load-balanced multi-instance run: attempt plan, access-log counting, pass criteria."""
from collections import Counter
import json
import random
import re
from urllib.parse import urlsplit

ORDER_CREATE_PATH = '/api/v1/orders'
ORDER_PAYMENT = re.compile(r'^/api/v1/orders/[^/]+/payment$')
REJECTION_REASONS = ['SALE_SOLD_OUT', 'PURCHASE_LIMIT_EXCEEDED']
UNROUTED = '(none)'


def plan_attempts(member_ids, sale_id, new_id, new_key, duplicate_every=10, shuffle_seed=None):
    """One request per user, plus same-user concurrent duplicates for every `duplicate_every`-th user.

    A duplicate user sends two replays of the primary key and two attempts with new keys. Which key wins the
    single purchase is a race, so every attempt of such a user may be rejected for either reason. A seed
    shuffles the release order so the primary key is not always the first to reach the load balancer.
    """
    plans = []

    def attempt(index, key, group, reason):
        plans.append({'id': new_id(), 'client': index, 'member_id': member_ids[index], 'key': key,
                      'sale_id': sale_id, 'quantity': 1, 'group': group, 'reason': reason})

    primary = [new_key() for _ in member_ids]
    for index in range(len(member_ids)):
        duplicate = index % duplicate_every == 0
        attempt(index, primary[index], 'primary', REJECTION_REASONS if duplicate else 'SALE_SOLD_OUT')
        if duplicate:
            for _ in range(2):
                attempt(index, primary[index], 'same_key_replay', REJECTION_REASONS)
            for _ in range(2):
                attempt(index, new_key(), 'different_key', REJECTION_REASONS)
    if shuffle_seed is not None:
        random.Random(shuffle_seed).shuffle(plans)
    return plans


def expected_counts(plans, stock):
    """Exact order statuses and the result-count ranges that hold whichever duplicate key wins."""
    replays = sum(p['group'] == 'same_key_replay' for p in plans)
    created = [stock, stock + replays]
    return ({'CREATED': created, 'REJECTED': [len(plans) - created[1], len(plans) - created[0]]},
            {'CONFIRMED': stock})


def parse_access_log(text):
    entries = []
    for line in text.splitlines():
        try:
            entry = json.loads(line)
        except ValueError:
            continue
        if isinstance(entry, dict) and isinstance(entry.get('request'), dict):
            entries.append(entry)
    return entries


def summarize_access_log(entries):
    """Per-upstream request counts for order creation, order payment and all requests."""
    summary = {'order_creates': Counter(), 'order_payments': Counter(), 'all_requests': Counter(),
               'auth_failures': 0}
    for entry in entries:
        request = entry['request']
        upstream = entry.get('upstream') or UNROUTED
        path = urlsplit(request.get('uri', '')).path
        summary['all_requests'][upstream] += 1
        if request.get('method') == 'POST' and path == ORDER_CREATE_PATH:
            summary['order_creates'][upstream] += 1
        elif request.get('method') == 'POST' and ORDER_PAYMENT.match(path):
            summary['order_payments'][upstream] += 1
        if entry.get('status') in (401, 403):
            summary['auth_failures'] += 1
    return {key: dict(value) if isinstance(value, Counter) else value for key, value in summary.items()}


def evaluate(result):
    """Return the failed pass criteria; an empty list means the run passed."""
    failures = []

    def check(condition, message):
        if not condition:
            failures.append(message)

    check(result['confirmed'] == result['stock'], f"confirmed {result['confirmed']} expected {result['stock']}")
    check(result['remaining'] == 0, f"remaining {result['remaining']} expected 0")
    check(result['verifier_violations'] == 0, f"verifier violations {result['verifier_violations']}")
    check(result['evidence_violations'] == 0, f"HTTP evidence violations {result['evidence_violations']}")
    check(result['sql_violations'] == 0, f"reconciliation violations {result['sql_violations']}")
    check(result['request_errors'] == 0, f"request errors {result['request_errors']}")
    check(result['access']['auth_failures'] == 0, f"401/403 responses {result['access']['auth_failures']}")
    creates = result['access']['order_creates']
    check(sum(creates.values()) == result['attempts'],
          f"access log has {sum(creates.values())} order creates for {result['attempts']} attempts")
    check(set(creates) == set(result['upstreams']),
          f"order creates reached {sorted(creates)} expected {sorted(result['upstreams'])}")
    return failures


def split(counts):
    total = sum(counts.values())
    return {upstream: {'requests': count, 'share': round(count / total, 4)}
            for upstream, count in sorted(counts.items())} if total else {}
