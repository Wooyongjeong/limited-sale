-- Order, reservation, stock and payment reconciliation for the sales schema.
--
-- Returns one row per violated invariant and nothing when the data is consistent. It is a single
-- read-only SELECT, so MySQL evaluates every branch against one consistent snapshot. Run it against the
-- sales database, for example:
--   mysql -h 127.0.0.1 -P "$MYSQL_PORT" -u "$MYSQL_USER" -p "$MYSQL_DATABASE" < tools/reconciliation/reconcile.sql
--
-- Orders that are still in flight (AWAITING_PAYMENT, PAYMENT_PENDING) are legitimate: a payment can be
-- SUCCEEDED before the order commits to CONFIRMED, so payment status is only compared with terminal orders.
SELECT check_name, sale_id, member_id, order_id, detail
FROM (
    -- Stock counters stay inside their bounds.
    SELECT 'SALE_QUANTITY_BOUNDS' AS check_name, s.id AS sale_id, CAST(NULL AS SIGNED) AS member_id,
           CAST(NULL AS SIGNED) AS order_id,
           CONVERT(CONCAT('total=', s.total_quantity, ' reserved=', s.reserved_quantity, ' sold=',
                           s.sold_quantity) USING utf8mb4) AS detail
    FROM sales s
    WHERE s.reserved_quantity < 0 OR s.sold_quantity < 0
       OR s.reserved_quantity + s.sold_quantity > s.total_quantity

    UNION ALL
    -- The sale counters equal what the reservation rows add up to.
    SELECT 'SALE_RESERVED_VS_RESERVATIONS', s.id, NULL, NULL,
           CONVERT(CONCAT('sale.reserved=', s.reserved_quantity, ' reservations=', COALESCE(t.reserved,
                           0)) USING utf8mb4)
    FROM sales s
    LEFT JOIN (SELECT sale_id,
                      SUM(CASE WHEN status = 'RESERVED' THEN quantity ELSE 0 END) AS reserved
               FROM sale_reservations GROUP BY sale_id) t ON t.sale_id = s.id
    WHERE s.reserved_quantity <> COALESCE(t.reserved, 0)

    UNION ALL
    SELECT 'SALE_SOLD_VS_RESERVATIONS', s.id, NULL, NULL,
           CONVERT(CONCAT('sale.sold=', s.sold_quantity, ' reservations=', COALESCE(t.sold, 0)) USING utf8mb4)
    FROM sales s
    LEFT JOIN (SELECT sale_id,
                      SUM(CASE WHEN status = 'CONFIRMED' THEN quantity ELSE 0 END) AS sold
               FROM sale_reservations GROUP BY sale_id) t ON t.sale_id = s.id
    WHERE s.sold_quantity <> COALESCE(t.sold, 0)

    UNION ALL
    -- Held plus confirmed reservations never exceed the stock, whatever the counters say.
    SELECT 'SALE_OVERSOLD', s.id, NULL, NULL,
           CONVERT(CONCAT('total=', s.total_quantity, ' active_reservations=', t.active) USING utf8mb4)
    FROM sales s
    JOIN (SELECT sale_id, SUM(quantity) AS active FROM sale_reservations
          WHERE status <> 'RELEASED' GROUP BY sale_id) t ON t.sale_id = s.id
    WHERE t.active > s.total_quantity

    UNION ALL
    -- Buyer counters stay inside the per-member limit.
    SELECT 'BUYER_QUANTITY_BOUNDS', b.sale_id, b.member_id, NULL,
           CONVERT(CONCAT('limit=', s.per_member_limit, ' reserved=', b.reserved_quantity,
                  ' purchased=', b.purchased_quantity) USING utf8mb4)
    FROM sale_buyer_quantities b
    JOIN sales s ON s.id = b.sale_id
    WHERE b.reserved_quantity < 0 OR b.purchased_quantity < 0
       OR b.reserved_quantity + b.purchased_quantity > s.per_member_limit

    UNION ALL
    -- Buyer counters equal their own reservation rows.
    SELECT 'BUYER_VS_RESERVATIONS', b.sale_id, b.member_id, NULL,
           CONVERT(CONCAT('buyer.reserved=', b.reserved_quantity, ' reservations=', COALESCE(t.reserved, 0),
                  ' buyer.purchased=', b.purchased_quantity, ' reservations=', COALESCE(t.confirmed, 0)) USING utf8mb4)
    FROM sale_buyer_quantities b
    LEFT JOIN (SELECT sale_id, member_id,
                      SUM(CASE WHEN status = 'RESERVED' THEN quantity ELSE 0 END) AS reserved,
                      SUM(CASE WHEN status = 'CONFIRMED' THEN quantity ELSE 0 END) AS confirmed
               FROM sale_reservations GROUP BY sale_id, member_id) t
           ON t.sale_id = b.sale_id AND t.member_id = b.member_id
    WHERE b.reserved_quantity <> COALESCE(t.reserved, 0) OR b.purchased_quantity <> COALESCE(t.confirmed, 0)

    UNION ALL
    -- No member holds or owns more than the limit, counted from reservations rather than counters.
    SELECT 'BUYER_LIMIT_EXCEEDED', r.sale_id, r.member_id, NULL,
           CONVERT(CONCAT('limit=', s.per_member_limit, ' active_reservations=', SUM(r.quantity)) USING utf8mb4)
    FROM sale_reservations r
    JOIN sales s ON s.id = r.sale_id
    WHERE r.status <> 'RELEASED'
    GROUP BY r.sale_id, r.member_id, s.per_member_limit
    HAVING SUM(r.quantity) > s.per_member_limit

    UNION ALL
    -- Every order has exactly one reservation, one payment, and one CREATED request result (v1) or one
    -- ORDER_CREATED acceptance whose ID is the order's request key (v2, stage 3).
    SELECT 'ORDER_MISSING_RESERVATION', o.sale_id, o.member_id, o.id, CONVERT('' USING utf8mb4)
    FROM purchase_orders o
    LEFT JOIN sale_reservations r ON r.order_id = o.id
    WHERE r.id IS NULL

    UNION ALL
    SELECT 'ORDER_MISSING_PAYMENT', o.sale_id, o.member_id, o.id, CONVERT('' USING utf8mb4)
    FROM purchase_orders o
    LEFT JOIN payments p ON p.order_id = o.id
    WHERE p.id IS NULL

    UNION ALL
    SELECT 'ORDER_MISSING_REQUEST_RESULT', o.sale_id, o.member_id, o.id, CONVERT('' USING utf8mb4)
    FROM purchase_orders o
    LEFT JOIN order_request_results q
           ON q.member_id = o.member_id AND q.request_key = o.request_key
          AND q.outcome = 'CREATED' AND q.order_id = o.id
    LEFT JOIN order_acceptances a
           ON a.order_id = o.id AND a.member_id = o.member_id AND a.public_id = o.request_key
          AND a.status = 'ORDER_CREATED'
    WHERE q.id IS NULL AND a.id IS NULL

    UNION ALL
    -- Order amounts and prices.
    SELECT 'ORDER_AMOUNT', o.sale_id, o.member_id, o.id,
           CONVERT(CONCAT('total=', o.total_amount, ' unit_price=', o.unit_price, ' quantity=',
                           o.quantity) USING utf8mb4)
    FROM purchase_orders o
    WHERE o.total_amount <> o.unit_price * o.quantity

    UNION ALL
    SELECT 'ORDER_UNIT_PRICE_VS_SALE', o.sale_id, o.member_id, o.id,
           CONVERT(CONCAT('order=', o.unit_price, ' sale=', s.unit_price) USING utf8mb4)
    FROM purchase_orders o
    JOIN sales s ON s.id = o.sale_id
    WHERE o.unit_price <> s.unit_price

    UNION ALL
    SELECT 'PAYMENT_AMOUNT_VS_ORDER', o.sale_id, o.member_id, o.id,
           CONVERT(CONCAT('payment=', p.amount, ' order=', o.total_amount) USING utf8mb4)
    FROM purchase_orders o
    JOIN payments p ON p.order_id = o.id
    WHERE p.amount <> o.total_amount

    UNION ALL
    -- A confirmed order has a succeeded payment and a confirmed reservation.
    SELECT 'CONFIRMED_ORDER_WITHOUT_SUCCEEDED_PAYMENT', o.sale_id, o.member_id, o.id,
           CONVERT(CONCAT('payment=', COALESCE(p.status, 'MISSING')) USING utf8mb4)
    FROM purchase_orders o
    LEFT JOIN payments p ON p.order_id = o.id
    WHERE o.status = 'CONFIRMED' AND (p.status IS NULL OR p.status <> 'SUCCEEDED')

    UNION ALL
    -- Failed and expired orders carry the matching closed payment.
    SELECT 'TERMINAL_ORDER_PAYMENT_STATUS', o.sale_id, o.member_id, o.id,
           CONVERT(CONCAT('order=', o.status, ' payment=', p.status) USING utf8mb4)
    FROM purchase_orders o
    JOIN payments p ON p.order_id = o.id
    WHERE (o.status = 'PAYMENT_FAILED' AND p.status <> 'FAILED')
       OR (o.status = 'EXPIRED' AND p.status <> 'CLOSED')

    UNION ALL
    -- Reservation identity and state follow the order.
    SELECT 'RESERVATION_VS_ORDER', r.sale_id, r.member_id, r.order_id,
           CONVERT(CONCAT('reservation=', r.sale_id, '/', r.member_id, '/', r.quantity,
                  ' order=', o.sale_id, '/', o.member_id, '/', o.quantity) USING utf8mb4)
    FROM sale_reservations r
    JOIN purchase_orders o ON o.id = r.order_id
    WHERE r.sale_id <> o.sale_id OR r.member_id <> o.member_id OR r.quantity <> o.quantity

    UNION ALL
    SELECT 'RESERVATION_STATUS_VS_ORDER', r.sale_id, r.member_id, r.order_id,
           CONVERT(CONCAT('order=', o.status, ' reservation=', r.status) USING utf8mb4)
    FROM sale_reservations r
    JOIN purchase_orders o ON o.id = r.order_id
    WHERE r.status <> CASE o.status WHEN 'CONFIRMED' THEN 'CONFIRMED'
                                    WHEN 'PAYMENT_FAILED' THEN 'RELEASED'
                                    WHEN 'EXPIRED' THEN 'RELEASED'
                                    ELSE 'RESERVED' END

    UNION ALL
    -- One request key resolves to one stored outcome, and that outcome matches the order table.
    SELECT 'DUPLICATE_ORDER_REQUEST_KEY', CAST(NULL AS SIGNED), o.member_id, CAST(NULL AS SIGNED),
           CONVERT(CONCAT('orders=', COUNT(*)) USING utf8mb4)
    FROM purchase_orders o
    GROUP BY o.member_id, o.request_key
    HAVING COUNT(*) > 1

    UNION ALL
    SELECT 'DUPLICATE_REQUEST_RESULT_KEY', CAST(NULL AS SIGNED), q.member_id, CAST(NULL AS SIGNED),
           CONVERT(CONCAT('results=', COUNT(*)) USING utf8mb4)
    FROM order_request_results q
    GROUP BY q.member_id, q.request_key
    HAVING COUNT(*) > 1

    UNION ALL
    SELECT 'REQUEST_RESULT_VS_ORDER', q.sale_id, q.member_id, q.order_id,
           CONVERT(CONCAT('outcome=', q.outcome) USING utf8mb4)
    FROM order_request_results q
    LEFT JOIN purchase_orders o ON o.id = q.order_id
    WHERE q.outcome = 'CREATED'
      AND (o.id IS NULL OR o.member_id <> q.member_id OR o.request_key <> q.request_key
           OR o.sale_id <> q.sale_id OR o.quantity <> q.quantity)

    UNION ALL
    -- An ORDER_CREATED acceptance names the order made for it: same member, sale and quantity, keyed by its ID.
    SELECT 'ACCEPTANCE_VS_ORDER', a.sale_id, a.member_id, a.order_id,
           CONVERT(CONCAT('status=', a.status) USING utf8mb4)
    FROM order_acceptances a
    LEFT JOIN purchase_orders o ON o.id = a.order_id
    WHERE a.status = 'ORDER_CREATED'
      AND (o.id IS NULL OR o.member_id <> a.member_id OR o.request_key <> a.public_id
           OR o.sale_id <> a.sale_id OR o.quantity <> a.quantity)

    UNION ALL
    SELECT 'REJECTED_REQUEST_HAS_ORDER', q.sale_id, q.member_id, o.id,
           CONVERT(CONCAT('outcome=', q.outcome, ' reason=', q.rejection_reason) USING utf8mb4)
    FROM order_request_results q
    JOIN purchase_orders o ON o.member_id = q.member_id AND o.request_key = q.request_key
    WHERE q.outcome = 'REJECTED'
) violations
ORDER BY check_name, sale_id, member_id, order_id;
