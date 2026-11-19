package com.wooyongjeong.limitedsale.order.infrastructure.persistence;

import com.wooyongjeong.limitedsale.order.domain.OrderBacklog;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaOrderBacklog implements OrderBacklog {
    private static final List<OrderStatus> UNFINISHED = List.of(OrderStatus.AWAITING_PAYMENT,
        OrderStatus.PAYMENT_PENDING);
    private final PurchaseOrderJpaRepository orders;

    public JpaOrderBacklog(PurchaseOrderJpaRepository orders) {
        this.orders = orders;
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
}
