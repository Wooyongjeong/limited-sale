#!/usr/bin/env python3
"""Check captured order attempts and cross-database snapshots against a fixed manifest."""
import argparse
from collections import Counter, defaultdict
import json
import math
import sys


def verify(data):
    errors = []

    def require(condition, message):
        if not condition:
            errors.append(message)

    def index(rows, key, label):
        result = {}
        for row in rows:
            value = key(row)
            require(value not in result, f'duplicate {label}: {value}')
            result[value] = row
        return result

    def same(a, b, keys):
        return all(a[k] == b[k] for k in keys)

    def duration(value):
        return type(value) in (int, float) and math.isfinite(value) and value >= 0

    manifest = data['manifest']
    require(type(data['final']) is bool, 'final must be boolean')
    plans = index(manifest['attempts'], lambda r: r['attempt_id'], 'planned attempt')
    attempts = index(data['requests'], lambda r: r['attempt_id'], 'attempt')
    require(bool(plans), 'manifest has no attempts')
    require(set(plans) == set(attempts), 'attempt coverage')
    planned_sales = index(manifest['sales'], lambda r: r['id'], 'planned sale')
    sales = index(data['sales'], lambda r: r['id'], 'sale')
    orders = index(data['orders'], lambda r: r['id'], 'order')
    reservations = index(data['reservations'], lambda r: r['order_id'], 'reservation')
    payments = index(data['payments'], lambda r: r['order_id'], 'payment')
    pg = index(data['pg_payments'], lambda r: r['pg_key'], 'PG key')
    buyers = index(data['buyers'], lambda r: (r['sale_id'], r['member_id']), 'buyer')
    results = index(data['request_results'], lambda r: (r['member_id'], r['key']), 'request result')
    index(data['orders'], lambda r: (r['member_id'], r['key']), 'order request key')
    index(data['payments'], lambda r: r['pg_key'], 'payment PG key')
    require(set(sales) == set(planned_sales), 'sale coverage')
    for sale_id in set(sales) & set(planned_sales):
        require(same(sales[sale_id], planned_sales[sale_id],
                     ('unit_price', 'total_quantity', 'per_member_limit')), f'sale {sale_id}: planned values')

    terminal = {'CONFIRMED': ('CONFIRMED', 'SUCCEEDED'),
                'PAYMENT_FAILED': ('RELEASED', 'FAILED'), 'EXPIRED': ('RELEASED', 'CLOSED')}
    rejection_reasons = {'SALE_NOT_FOUND', 'SALE_OUTSIDE_PERIOD', 'SALE_STOPPED',
                         'PURCHASE_LIMIT_EXCEEDED', 'SALE_SOLD_OUT'}
    totals = defaultdict(Counter)
    buyer_totals = defaultdict(Counter)
    for order_id, order in orders.items():
        reservation = reservations.get(order_id)
        payment = payments.get(order_id)
        sale = sales.get(order['sale_id'])
        require(sale is not None, f'order {order_id}: missing sale')
        if sale is not None:
            require(order['unit_price'] == sale['unit_price'], f'order {order_id}: unit price')
        require(order['total_amount'] == order['unit_price'] * order['quantity'], f'order {order_id}: amount')
        require(reservation is not None, f'order {order_id}: missing reservation')
        require(payment is not None, f'order {order_id}: missing payment')
        result = results.get((order['member_id'], order['key']))
        require(result is not None and result['outcome'] == 'CREATED' and result['order_id'] == order_id,
                f'order {order_id}: missing created request result')
        if reservation is not None:
            require(same(reservation, order, ('sale_id', 'member_id', 'quantity')),
                    f'order {order_id}: reservation identity')
            status = reservation['status']
            require(status in ('RESERVED', 'CONFIRMED', 'RELEASED'), f'order {order_id}: reservation status')
            totals[order['sale_id']][status] += reservation['quantity']
            buyer_totals[(order['sale_id'], order['member_id'])][status] += reservation['quantity']
            require(status == terminal.get(order['status'], ('RESERVED', None))[0],
                    f'order {order_id}: reservation state')
        require(order['status'] in (*terminal, 'AWAITING_PAYMENT', 'PAYMENT_PENDING'), f'order {order_id}: status')
        if data['final']:
            require(order['status'] in terminal, f'order {order_id}: not final')
        if payment is not None:
            require(payment['amount'] == order['total_amount'], f'order {order_id}: payment amount')
            remote = pg.get(payment['pg_key'])
            if order['status'] in terminal:
                require(payment['status'] == terminal[order['status']][1], f'order {order_id}: payment status')
                require(remote is not None, f'order {order_id}: missing PG result')
            if remote is not None:
                require(remote['amount'] == payment['amount'], f'order {order_id}: PG amount')
                if data['final']:
                    require(remote['status'] == payment['status'], f'order {order_id}: PG status')
    for row in data['reservations']:
        require(row['order_id'] in orders, f"orphan reservation: {row['order_id']}")
    for row in data['payments']:
        require(row['order_id'] in orders, f"orphan payment: {row['order_id']}")
    payment_keys = {p['pg_key'] for p in data['payments']}
    for key in pg:
        require(key in payment_keys, f'orphan PG payment: {key}')
    for sale_id, sale in sales.items():
        reserved, sold = sale['reserved_quantity'], sale['sold_quantity']
        require(reserved >= 0 and sold >= 0 and reserved + sold <= sale['total_quantity'], f'sale {sale_id}: bounds')
        require((reserved, sold) == (totals[sale_id]['RESERVED'], totals[sale_id]['CONFIRMED']),
                f'sale {sale_id}: reservation totals')
    require(set(buyers) == set(buyer_totals), 'buyer coverage')
    for key in set(buyer_totals) | set(buyers):
        buyer = buyers.get(key)
        require(buyer is not None and key[0] in sales, f'buyer {key}: missing buyer or sale')
        if buyer is not None and key[0] in sales:
            reserved, purchased = buyer['reserved_quantity'], buyer['purchased_quantity']
            require(reserved >= 0 and purchased >= 0 and reserved + purchased <= sales[key[0]]['per_member_limit'],
                    f'buyer {key}: bounds')
            require((reserved, purchased) == (buyer_totals[key]['RESERVED'], buyer_totals[key]['CONFIRMED']),
                    f'buyer {key}: reservation totals')
    for row in data['request_results']:
        if row['outcome'] == 'CREATED':
            order = orders.get(row['order_id'])
            require(order is not None and same(order, row, ('member_id', 'key', 'sale_id', 'quantity'))
                    and row['rejection_reason'] is None, f"request {row['key']}: order mismatch")
        else:
            require(row['outcome'] == 'REJECTED' and row['order_id'] is None
                    and row['rejection_reason'] in rejection_reasons, f"request {row['key']}: outcome")

    matched_results = set()
    for attempt_id in set(plans) & set(attempts):
        plan, attempt = plans[attempt_id], attempts[attempt_id]
        require(same(plan, attempt, ('member_id', 'key', 'sale_id', 'quantity')),
                f'attempt {attempt_id}: input mismatch')
        require(attempt['sent'] is True, f'attempt {attempt_id}: not sent')
        outcome = attempt['result']
        allowed = plan['allowed_results']
        require(bool(allowed) and all(x in ('CREATED', 'REJECTED', 'CONFLICT') for x in allowed),
                f'attempt {attempt_id}: invalid allowed results')
        require(outcome in allowed, f'attempt {attempt_id}: unexpected result {outcome}')
        result_key = (attempt['member_id'], attempt['key'])
        saved = results.get(result_key)
        if outcome in ('CREATED', 'REJECTED'):
            require(saved is not None and saved['outcome'] == outcome
                    and same(saved, attempt, ('sale_id', 'quantity', 'order_id', 'rejection_reason')),
                    f'attempt {attempt_id}: saved result mismatch')
            if saved is not None:
                matched_results.add(result_key)
            if outcome == 'REJECTED':
                require(attempt['rejection_reason'] == plan['rejection_reason']
                        and plan['rejection_reason'] in rejection_reasons,
                        f'attempt {attempt_id}: rejection reason')
            if outcome == 'CREATED':
                require(attempt['rejection_reason'] is None, f'attempt {attempt_id}: unexpected rejection')
                order = orders.get(attempt['order_id'])
                if data['final']:
                    require(order is not None and isinstance(plan['final_statuses'], list)
                            and order['status'] in plan['final_statuses'], f'attempt {attempt_id}: final status')
        elif outcome == 'CONFLICT':
            require(attempt['order_id'] is None and attempt['rejection_reason'] is None,
                    f'attempt {attempt_id}: conflict response')
            require(saved is not None and not same(saved, attempt, ('sale_id', 'quantity')),
                    f'attempt {attempt_id}: conflict did not change input')
    require(matched_results == set(results), 'saved request result coverage')
    require(Counter(r['result'] for r in data['requests']) == Counter(manifest['expected_results']), 'result counts')
    require(Counter(o['status'] for o in data['orders']) == Counter(manifest['expected_order_statuses']),
            'order status counts')
    recovery = data['recovery']
    required = manifest['recovery_required']
    require(type(required) is bool, 'recovery_required must be boolean')
    require((recovery is not None) == required, 'recovery requirement')
    if recovery is not None:
        require(data['final'] is True, 'recovery requires final snapshot')
        deadline = recovery['deadline_seconds']
        end = recovery['end_elapsed_seconds']
        require(duration(deadline) and 0 < deadline <= 120, 'recovery deadline')
        require(duration(end) and duration(deadline) and end <= deadline, 'recovery end')
        targets = index(recovery['targets'], lambda r: r['order_id'], 'recovery target')
        require(set(targets) == set(orders), 'recovery target coverage')
        for order_id, target in targets.items():
            elapsed = target['elapsed_seconds']
            require(duration(elapsed) and duration(end) and elapsed <= end,
                    f'order {order_id}: recovery terminal time')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('snapshot', help='sanitized JSON snapshot')
    args = parser.parse_args()
    try:
        with open(args.snapshot, encoding='utf-8') as source:
            errors = verify(json.load(source))
    except (OSError, ValueError, KeyError, TypeError, OverflowError) as exc:
        print(f'invalid snapshot: {exc}', file=sys.stderr)
        return 2
    for error in errors:
        print(error, file=sys.stderr)
    print(f'{len(errors)} invariant failure(s)')
    return 1 if errors else 0


if __name__ == '__main__':
    sys.exit(main())
