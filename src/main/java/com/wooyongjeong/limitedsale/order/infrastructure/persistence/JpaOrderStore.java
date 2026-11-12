package com.wooyongjeong.limitedsale.order.infrastructure.persistence;

import com.wooyongjeong.limitedsale.order.domain.OrderRejection;
import com.wooyongjeong.limitedsale.order.domain.OrderRequestKeyCollision;
import com.wooyongjeong.limitedsale.order.domain.OrderRequestOutcome;
import com.wooyongjeong.limitedsale.order.domain.OrderStore;
import com.wooyongjeong.limitedsale.order.domain.OrderView;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
public class JpaOrderStore implements OrderStore {
    private static final Set<String> REQUEST_KEYS = Set.of("uk_orders_request", "uk_order_request_results_member_key");
    private final PurchaseOrderJpaRepository orders;
    private final OrderRequestResultJpaRepository results;

    public JpaOrderStore(PurchaseOrderJpaRepository orders, OrderRequestResultJpaRepository results) {
        this.orders = orders;
        this.results = results;
    }

    @Override
    public Optional<OrderRequestOutcome> request(long memberId, UUID key) {
        return results.findByMemberIdAndRequestKey(memberId, key).map(OrderRequestResultEntity::toOutcome);
    }

    @Override
    public long create(long memberId, UUID key, long saleId, long productId, String productName,
            long unitPrice, int quantity, Instant now, Instant expiresAt) {
        var order = new PurchaseOrderEntity(memberId, key, saleId, productId, productName, unitPrice,
            quantity, now, expiresAt);
        try {
            return orders.saveAndFlush(order).id();
        } catch (DataIntegrityViolationException failure) {
            throw classify(failure);
        }
    }

    @Override
    public void record(long memberId, UUID key, long saleId, int quantity, Long orderId, OrderRejection rejection,
            Instant now) {
        try {
            results.saveAndFlush(new OrderRequestResultEntity(memberId, key, saleId, quantity, orderId,
                rejection, now));
        } catch (DataIntegrityViolationException failure) {
            throw classify(failure);
        }
    }

    @Override
    public Optional<OrderView> find(long memberId, UUID publicId) {
        return orders.findByMemberIdAndPublicId(memberId, publicId).map(PurchaseOrderEntity::view);
    }

    @Override
    public OrderView find(long orderId) {
        return orders.findById(orderId).orElseThrow().view();
    }

    private RuntimeException classify(DataIntegrityViolationException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getSQLException() != null
                    && violation.getSQLException().getErrorCode() == 1062 && violation.getConstraintName() != null) {
                String name = violation.getConstraintName().replace("`", "").replace("'", "");
                int dot = name.lastIndexOf('.');
                if (REQUEST_KEYS.contains(name.substring(dot + 1))) {
                    return new OrderRequestKeyCollision(failure);
                }
            }
        }
        return failure;
    }
}
