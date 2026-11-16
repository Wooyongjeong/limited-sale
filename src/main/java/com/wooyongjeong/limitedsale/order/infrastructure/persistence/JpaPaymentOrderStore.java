package com.wooyongjeong.limitedsale.order.infrastructure.persistence;

import com.wooyongjeong.limitedsale.order.domain.PaymentOrder;
import com.wooyongjeong.limitedsale.order.domain.PaymentOrderStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JpaPaymentOrderStore implements PaymentOrderStore {
    private final PurchaseOrderJpaRepository orders;

    public JpaPaymentOrderStore(PurchaseOrderJpaRepository orders) {
        this.orders = orders;
    }

    @Override
    public Optional<PaymentOrder> lock(long memberId, UUID publicId) {
        return orders.lockOwned(memberId, publicId).map(PurchaseOrderEntity::paymentOrder);
    }

    @Override
    public PaymentOrder lock(long orderId) {
        return orders.lockById(orderId).orElseThrow().paymentOrder();
    }

    @Override
    public Instant databaseTime() {
        return Instant.parse(orders.databaseTime());
    }

    @Override
    public List<Long> candidates(int limit) {
        return orders.candidates(limit);
    }

    @Override
    public Optional<PaymentOrder> tryLock(long orderId) {
        return orders.tryLock(orderId).map(PurchaseOrderEntity::paymentOrder);
    }

    @Override
    public void claim(long orderId, UUID token, Instant until) {
        orders.lockById(orderId).orElseThrow().claim(token, until);
    }

    @Override
    public void reschedule(long orderId, Instant next, boolean review, Instant nextWarning) {
        orders.lockById(orderId).orElseThrow().reschedule(next, review, nextWarning);
    }

    @Override
    public void release(long orderId) {
        orders.lockById(orderId).orElseThrow().release();
    }

    @Override
    public void pending(long orderId, Instant now, Instant retryAt) {
        orders.lockById(orderId).orElseThrow().pending(now, retryAt);
    }

    @Override
    public void finish(long orderId, String status, Instant now) {
        orders.lockById(orderId).orElseThrow().finish(status, now);
    }
}
