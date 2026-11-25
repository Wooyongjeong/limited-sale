package com.wooyongjeong.limitedsale.order.infrastructure.persistence;

import com.wooyongjeong.limitedsale.order.domain.OrderBacklog;
import java.time.Duration;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaOrderBacklog implements OrderBacklog {
    private static final List<OrderStatus> UNFINISHED = List.of(OrderStatus.AWAITING_PAYMENT,
        OrderStatus.PAYMENT_PENDING);
    private final PurchaseOrderJpaRepository orders;
    private final JdbcTemplate jdbc;

    public JpaOrderBacklog(PurchaseOrderJpaRepository orders, JdbcTemplate jdbc) {
        this.orders = orders;
        this.jdbc = jdbc;
    }

    @Override
    public long unfinished() {
        return orders.countByStatusIn(UNFINISHED);
    }

    @Override
    public long requiringReview() {
        // The status filter keeps the count on ix_orders_recovery; finished orders never keep the review flag.
        return orders.countByStatusInAndRequiresReviewTrue(UNFINISHED);
    }

    @Override
    public Queue unpublishedOutbox() {
        // ix_order_outbox_unpublished (published_at, id) narrows this to the unpublished rows.
        return queue("SELECT COUNT(*), TIMESTAMPDIFF(MICROSECOND, MIN(created_at), UTC_TIMESTAMP(6)) FROM order_outbox "
            + "WHERE published_at IS NULL");
    }

    @Override
    public Queue acceptedRequests() {
        // ix_order_acceptances_status (status, id) narrows this to the unfinished acceptances.
        return queue("SELECT COUNT(*), TIMESTAMPDIFF(MICROSECOND, MIN(accepted_at), UTC_TIMESTAMP(6)) "
            + "FROM order_acceptances WHERE status = 'ACCEPTED'");
    }

    private Queue queue(String sql) {
        return jdbc.queryForObject(sql, (rows, number) -> {
            long size = rows.getLong(1);
            long oldestMicros = rows.getLong(2);
            return new Queue(size, size == 0 ? Duration.ZERO : Duration.ofNanos(Math.max(0, oldestMicros) * 1000));
        });
    }
}
