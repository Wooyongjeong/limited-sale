package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.OrderStore;
import com.wooyongjeong.limitedsale.order.domain.PaymentOrder;
import com.wooyongjeong.limitedsale.order.domain.PaymentOrderStore;
import com.wooyongjeong.limitedsale.order.domain.RecoveryPolicy;
import com.wooyongjeong.limitedsale.payment.api.GatewayResult;
import com.wooyongjeong.limitedsale.payment.api.PaymentDetails;
import com.wooyongjeong.limitedsale.payment.api.PaymentOperations;
import com.wooyongjeong.limitedsale.sale.api.SaleReservation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class OrderPaymentTransaction {
    private static final Logger log = LoggerFactory.getLogger(OrderPaymentTransaction.class);
    private final PaymentOrderStore orders;
    private final OrderStore views;
    private final PaymentOperations payments;
    private final SaleReservation sales;
    private final Duration retryInterval;
    private final Duration leaseDuration;
    private final Duration warningInterval;

    public OrderPaymentTransaction(PaymentOrderStore orders, OrderStore views, PaymentOperations payments,
            SaleReservation sales, @Value("${limited-sale.payment.retry-interval:PT10S}") Duration retryInterval,
            @Value("${limited-sale.recovery.lease-duration:PT30S}") Duration leaseDuration,
            @Value("${limited-sale.recovery.warning-interval:PT5M}") Duration warningInterval) {
        if (retryInterval.isNegative() || retryInterval.isZero() || leaseDuration.isNegative()
                || leaseDuration.isZero() || warningInterval.isNegative() || warningInterval.isZero()) {
            throw new IllegalArgumentException("Recovery durations must be positive");
        }
        this.orders = orders;
        this.views = views;
        this.payments = payments;
        this.sales = sales;
        this.retryInterval = retryInterval;
        this.leaseDuration = leaseDuration;
        this.warningInterval = warningInterval;
    }

    @Transactional
    public PaymentAttempt begin(long memberId, UUID publicId) {
        var found = orders.lock(memberId, publicId);
        if (found.isEmpty()) {
            return new PaymentAttempt(0, null, PaymentAttempt.Action.NOT_FOUND, null);
        }
        return beginLocked(found.get(), true);
    }

    @Transactional
    public List<Long> candidates(int limit) {
        return orders.candidates(limit);
    }

    @Transactional
    public PaymentAttempt beginRecovery(long orderId) {
        var found = orders.tryLock(orderId);
        return found.map(order -> beginLocked(order, false))
                .orElse(new PaymentAttempt(orderId, null, PaymentAttempt.Action.WAIT, null));
    }

    private PaymentAttempt beginLocked(PaymentOrder order, boolean userRequest) {
        if (terminalOrder(order.status())) {
            return new PaymentAttempt(order.id(), null, PaymentAttempt.Action.DONE, null);
        }
        PaymentDetails payment = payments.lock(order.id());
        Instant now = orders.databaseTime();
        if (order.leaseUntil() != null && now.isBefore(order.leaseUntil())) {
            return new PaymentAttempt(order.id(), null, PaymentAttempt.Action.WAIT, null);
        }
        boolean expiryDue = !now.isBefore(order.expiresAt()) && !order.requiresReview();
        if (!userRequest && !expiryDue
                && (order.nextRecoveryAt() == null || now.isBefore(order.nextRecoveryAt()))) {
            return new PaymentAttempt(order.id(), null, PaymentAttempt.Action.WAIT, null);
        }
        RecoveryPolicy.Action decision = RecoveryPolicy.action(payment.status(), order.expiresAt(), now, userRequest);
        if (decision == RecoveryPolicy.Action.WAIT || (userRequest && now.isBefore(order.expiresAt())
                && decision == RecoveryPolicy.Action.QUERY && order.nextRecoveryAt() != null
                && now.isBefore(order.nextRecoveryAt()))) {
            return new PaymentAttempt(order.id(), payment, PaymentAttempt.Action.WAIT, null);
        }
        UUID token = UUID.randomUUID();
        orders.claim(order.id(), token, now.plus(leaseDuration));
        if (decision != RecoveryPolicy.Action.FINISH) {
            Instant next = now.plus(retryInterval);
            if (now.isBefore(order.expiresAt()) && order.expiresAt().isBefore(next)) {
                next = order.expiresAt();
            }
            orders.pending(order.id(), now, next);
            if (decision == RecoveryPolicy.Action.CHARGE) {
                payment = payments.markStarted(order.id(), now);
            }
        }
        PaymentAttempt.Action action = decision == RecoveryPolicy.Action.FINISH
                ? PaymentAttempt.Action.DONE : PaymentAttempt.Action.valueOf(decision.name());
        return new PaymentAttempt(order.id(), payment, action, token);
    }

    @Transactional
    public PaymentAttempt.Action afterMissing(long orderId, UUID token) {
        PaymentOrder order = orders.lock(orderId);
        Instant now = orders.databaseTime();
        if (!owned(order, token, now)) {
            return PaymentAttempt.Action.WAIT;
        }
        return now.isBefore(order.expiresAt()) ? PaymentAttempt.Action.CHARGE : PaymentAttempt.Action.CLOSE;
    }

    @Transactional
    public boolean record(long orderId, UUID token, GatewayResult result) {
        PaymentOrder order = orders.lock(orderId);
        payments.lock(orderId);
        Instant now = orders.databaseTime();
        if (!owned(order, token, now)) {
            return false;
        }
        payments.record(orderId, result == GatewayResult.MISSING ? "UNKNOWN" : result.name(), now);
        ensureStillOwned(order, token);
        return true;
    }

    @Transactional
    public OrderResult finish(long orderId, UUID token, boolean expiryCloseAttempted) {
        PaymentOrder order = orders.lock(orderId);
        PaymentDetails payment = payments.lock(orderId);
        Instant now = orders.databaseTime();
        if (owned(order, token, now)) {
            if (!terminalOrder(order.status()) && terminalPayment(payment.status())) {
                boolean success = "SUCCEEDED".equals(payment.status());
                sales.finish(order.id(), order.saleId(), order.memberId(), order.quantity(), success, now);
                String status = success ? "CONFIRMED"
                        : "FAILED".equals(payment.status()) ? "PAYMENT_FAILED" : "EXPIRED";
                orders.finish(order.id(), status, now);
                ensureStillOwned(order, token);
                order = orders.lock(orderId);
            } else if (!terminalOrder(order.status())) {
                boolean review = order.requiresReview() || !now.isBefore(order.expiresAt());
                Instant warning = order.nextReviewWarningAt();
                if (review && (warning == null || !now.isBefore(warning))) {
                    UUID publicId = order.publicId();
                    // Only report the persisted transition; a later lease check or flush can still roll it back.
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            log.warn("Order {} requires payment review; recovery will retry", publicId);
                        }
                    });
                    warning = now.plus(warningInterval);
                }
                Instant next = now.plus(retryInterval);
                if (!now.isBefore(order.expiresAt()) && !expiryCloseAttempted) {
                    next = now;
                } else if (now.isBefore(order.expiresAt()) && order.expiresAt().isBefore(next)) {
                    next = order.expiresAt();
                }
                orders.reschedule(orderId, next, review, warning);
                orders.release(orderId);
                ensureStillOwned(order, token);
            }
        }
        return result(order);
    }

    @Transactional
    public OrderResult current(long orderId) {
        return result(orders.lock(orderId));
    }

    private OrderResult result(PaymentOrder order) {
        OrderResult.Kind kind = switch (order.status()) {
            case "CONFIRMED" -> OrderResult.Kind.PAYMENT_SUCCEEDED;
            case "PAYMENT_FAILED" -> OrderResult.Kind.PAYMENT_DECLINED;
            case "EXPIRED" -> OrderResult.Kind.ORDER_EXPIRED;
            default -> OrderResult.Kind.PAYMENT_PENDING;
        };
        return new OrderResult(kind, views.find(order.id()));
    }

    private void ensureStillOwned(PaymentOrder order, UUID token) {
        if (!owned(order, token, orders.databaseTime())) {
            throw new IllegalStateException("Order recovery lease expired before commit");
        }
    }

    private boolean owned(PaymentOrder order, UUID token, Instant now) {
        return RecoveryPolicy.owns(token, order.leaseToken(), order.leaseUntil(), now);
    }

    private boolean terminalOrder(String status) {
        return "CONFIRMED".equals(status) || "PAYMENT_FAILED".equals(status) || "EXPIRED".equals(status);
    }

    private boolean terminalPayment(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CLOSED".equals(status);
    }
}
