package com.wooyongjeong.limitedsale.order.infrastructure.persistence;

import com.wooyongjeong.limitedsale.order.domain.AcceptanceInProcess;
import com.wooyongjeong.limitedsale.order.domain.AcceptanceMessage;
import com.wooyongjeong.limitedsale.order.domain.OrderAcceptance;
import com.wooyongjeong.limitedsale.order.domain.OrderAcceptanceStore;
import com.wooyongjeong.limitedsale.order.domain.OrderRejection;
import com.wooyongjeong.limitedsale.order.domain.OrderRequestKeyCollision;
import com.wooyongjeong.limitedsale.order.domain.ProcessingFailure;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
public class JpaOrderAcceptanceStore implements OrderAcceptanceStore {
    private static final String REQUEST_KEY = "uk_order_acceptances_member_key";
    private final OrderAcceptanceJpaRepository acceptances;
    private final OrderOutboxJpaRepository outbox;
    private final PurchaseOrderJpaRepository orders;

    public JpaOrderAcceptanceStore(OrderAcceptanceJpaRepository acceptances, OrderOutboxJpaRepository outbox,
            PurchaseOrderJpaRepository orders) {
        this.acceptances = acceptances;
        this.outbox = outbox;
        this.orders = orders;
    }

    @Override
    public Optional<OrderAcceptance> request(long memberId, UUID key) {
        return acceptances.findByMemberIdAndRequestKey(memberId, key).map(this::view);
    }

    @Override
    public OrderAcceptance accept(UUID acceptanceId, long memberId, UUID key, long saleId, int quantity, Instant now,
            AcceptanceMessage message) {
        var acceptance = save(OrderAcceptanceEntity.accepted(acceptanceId, memberId, key, saleId, quantity, now));
        // Flushed on its own: an outbox failure here rolls the acceptance back with it, as one transaction.
        outbox.saveAndFlush(new OrderOutboxEntity(message, acceptance.id(), now));
        return acceptance.toAcceptance(null);
    }

    @Override
    public OrderAcceptance reject(UUID acceptanceId, long memberId, UUID key, long saleId, int quantity,
            OrderRejection rejection, Instant now) {
        return save(OrderAcceptanceEntity.rejected(acceptanceId, memberId, key, saleId, quantity, rejection, now))
            .toAcceptance(null);
    }

    @Override
    public Optional<OrderAcceptance> find(long memberId, UUID acceptanceId) {
        return acceptances.findByMemberIdAndPublicId(memberId, acceptanceId).map(this::view);
    }

    @Override
    public Optional<AcceptanceInProcess> lockForProcessing(UUID acceptanceId) {
        return acceptances.findForUpdate(acceptanceId).map(OrderAcceptanceEntity::toInProcess);
    }

    @Override
    public void finishWithOrder(UUID acceptanceId, long orderId, Instant now) {
        locked(acceptanceId).orderCreated(orderId, now);
    }

    @Override
    public void finishRejected(UUID acceptanceId, OrderRejection rejection, Instant now) {
        locked(acceptanceId).rejected(rejection, now);
    }

    @Override
    public void finishFailed(UUID acceptanceId, ProcessingFailure reason, Instant now) {
        locked(acceptanceId).failed(reason, now);
    }

    /** The row is locked by {@link #lockForProcessing} already; this returns the same managed entity. */
    private OrderAcceptanceEntity locked(UUID acceptanceId) {
        return acceptances.findForUpdate(acceptanceId).orElseThrow();
    }

    private OrderAcceptanceEntity save(OrderAcceptanceEntity entity) {
        try {
            return acceptances.saveAndFlush(entity);
        } catch (DataIntegrityViolationException failure) {
            throw classify(failure);
        }
    }

    private OrderAcceptance view(OrderAcceptanceEntity entity) {
        Long orderId = entity.orderId();
        return entity.toAcceptance(orderId == null ? null : orders.findById(orderId).orElseThrow().publicId());
    }

    private RuntimeException classify(DataIntegrityViolationException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getSQLException() != null
                    && violation.getSQLException().getErrorCode() == 1062 && violation.getConstraintName() != null) {
                String name = violation.getConstraintName().replace("`", "").replace("'", "");
                if (name.substring(name.lastIndexOf('.') + 1).equals(REQUEST_KEY)) {
                    return new OrderRequestKeyCollision(failure);
                }
            }
        }
        return failure;
    }
}
