package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.payment.api.GatewayResult;
import com.wooyongjeong.limitedsale.payment.api.PaymentGateway;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderPaymentService {
    private final OrderPaymentTransaction transaction;
    private final PaymentGateway gateway;
    private final Duration budget;

    public OrderPaymentService(OrderPaymentTransaction transaction, PaymentGateway gateway,
            @Value("${limited-sale.payment.timeout:PT3S}") Duration budget) {
        if (budget.isZero() || budget.isNegative()) {
            throw new IllegalArgumentException("Payment timeout must be positive");
        }
        this.transaction = transaction;
        this.gateway = gateway;
        this.budget = budget;
    }

    @Transactional(propagation = Propagation.NEVER)
    public OrderResult pay(long memberId, UUID publicId) {
        PaymentAttempt attempt = transaction.begin(memberId, publicId);
        if (attempt.action() == PaymentAttempt.Action.NOT_FOUND) {
            return new OrderResult(OrderResult.Kind.ORDER_NOT_FOUND, null);
        }
        if (attempt.action() == PaymentAttempt.Action.DONE || attempt.action() == PaymentAttempt.Action.WAIT) {
            return transaction.finish(attempt.orderId());
        }
        long started = System.nanoTime();
        GatewayResult result = switch (attempt.action()) {
            case CHARGE -> gateway.charge(attempt.payment(), budget);
            case CLOSE -> gateway.close(attempt.payment(), budget);
            case QUERY -> gateway.query(attempt.payment(), budget);
            default -> throw new IllegalStateException("Unexpected payment action");
        };
        if (result == GatewayResult.MISSING) {
            Duration remaining = budget.minusNanos(Math.max(0, System.nanoTime() - started));
            if (!remaining.isNegative() && !remaining.isZero()) {
                result = transaction.expired(attempt.orderId())
                        ? gateway.close(attempt.payment(), remaining)
                        : gateway.charge(attempt.payment(), remaining);
            } else {
                result = GatewayResult.UNKNOWN;
            }
        }
        transaction.record(attempt.orderId(), result);
        return transaction.finish(attempt.orderId());
    }
}
