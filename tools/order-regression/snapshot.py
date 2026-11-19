"""Read sales and PG state for cross-database verification."""
import json


def rows(env, which, db, sql):
    text = env.mysql(which, f'USE {db};\n{sql}')
    return [json.loads(line) for line in text.splitlines() if line]


def consistent_rows(env, which, db, queries):
    # Separate connections could observe one order commit half applied across tables.
    statements = ['SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;',
                  'START TRANSACTION WITH CONSISTENT SNAPSHOT;']
    for name, sql in queries.items():
        statements += [f"""SELECT '{{"section":"{name}"}}';""", sql]
    statements.append('COMMIT;')
    sections = {name: [] for name in queries}
    current = None
    for row in rows(env, which, db, '\n'.join(statements)):
        if set(row) == {'section'}:
            current = sections[row['section']]
        else:
            current.append(row)
    return sections


def collect(env, sale_id):
    sections = consistent_rows(env, 'sales', 'limited_sale', {
        'sales': f"SELECT JSON_OBJECT('id',id,'unit_price',unit_price,'total_quantity',total_quantity,"
                 f"'reserved_quantity',reserved_quantity,'sold_quantity',sold_quantity,'per_member_limit',per_member_limit) FROM sales WHERE id={sale_id};",
        'buyers': f"SELECT JSON_OBJECT('sale_id',sale_id,'member_id',member_id,'reserved_quantity',reserved_quantity,"
                  f"'purchased_quantity',purchased_quantity) FROM sale_buyer_quantities WHERE sale_id={sale_id};",
        'orders': f"SELECT JSON_OBJECT('id',id,'member_id',member_id,'key',BIN_TO_UUID(request_key),'sale_id',sale_id,"
                  f"'quantity',quantity,'unit_price',unit_price,'total_amount',total_amount,'status',status,'public_id',BIN_TO_UUID(public_id)) FROM purchase_orders WHERE sale_id={sale_id};",
        'reservations': f"SELECT JSON_OBJECT('order_id',order_id,'sale_id',sale_id,'member_id',member_id,'quantity',quantity,"
                        f"'status',status) FROM sale_reservations WHERE sale_id={sale_id};",
        'payments': f"SELECT JSON_OBJECT('order_id',p.order_id,'pg_key',BIN_TO_UUID(p.pg_key),'amount',p.amount,'status',p.status) "
                    f"FROM payments p JOIN purchase_orders o ON o.id=p.order_id WHERE o.sale_id={sale_id};",
        'request_results': f"SELECT JSON_OBJECT('member_id',member_id,'key',BIN_TO_UUID(request_key),'sale_id',sale_id,'quantity',quantity,"
                           f"'outcome',outcome,'order_id',order_id,'rejection_reason',rejection_reason) FROM order_request_results WHERE sale_id={sale_id};",
    })
    pg = rows(env, 'pg', 'mock_pg', "SELECT JSON_OBJECT('pg_key',BIN_TO_UUID(m.pg_key),'amount',m.amount,"
              "'status',m.status,'scenario',m.scenario,'delay_seconds',s.delay_seconds) FROM mock_payments m "
              "LEFT JOIN mock_scenario_settings s ON s.pg_key=m.pg_key;")
    return {**sections, 'pg_payments': pg}
