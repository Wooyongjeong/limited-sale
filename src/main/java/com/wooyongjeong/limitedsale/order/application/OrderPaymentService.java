package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.OrderRateLimit.Operation;
import com.wooyongjeong.limitedsale.payment.api.GatewayResult;
import com.wooyongjeong.limitedsale.payment.api.PaymentGateway;
import java.time.Duration;
import java.util.Optional;
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
    private final OrderMetrics metrics;
    private final OrderRateLimiter rateLimiter;

    public OrderPaymentService(OrderPaymentTransaction transaction, PaymentGateway gateway, OrderMetrics metrics,
            OrderRateLimiter rateLimiter,
            @Value("${limited-sale.payment.timeout:PT3S}") Duration budget) {
        if (budget.isZero() || budget.isNegative()) {
            throw new IllegalArgumentException("Payment timeout must be positive");
        }
        this.transaction = transaction;
        this.gateway = gateway;
        this.budget = budget;
        this.metrics = metrics;
        this.rateLimiter = rateLimiter;
    }

    @Transactional(propagation = Propagation.NEVER)
    public OrderResult pay(long memberId, UUID publicId) {
        Optional<OrderResult> rejected = rateLimiter.reject(memberId, Operation.PAYMENT);
        if (rejected.isPresent()) {
            return rejected.get();
        }
        OrderResult result = execute(transaction.begin(memberId, publicId));
        metrics.payment(result.kind());
        return result;
    }

    @Transactional(propagation = Propagation.NEVER)
    public void recover(long orderId) {
        PaymentAttempt attempt = transaction.beginRecovery(orderId);
        if (attempt.token() != null) {
            execute(attempt);
        }
    }

    private OrderResult execute(PaymentAttempt attempt) {
        if (attempt.action() == PaymentAttempt.Action.NOT_FOUND) {
            return new OrderResult(OrderResult.Kind.ORDER_NOT_FOUND, null);
        }
        if (attempt.action() == PaymentAttempt.Action.WAIT || attempt.token() == null) {
            return transaction.current(attempt.orderId());
        }
        if (attempt.action() == PaymentAttempt.Action.DONE) {
            return transaction.finish(attempt.orderId(), attempt.token(), false);
        }
        boolean expiryCloseAttempted = attempt.action() == PaymentAttempt.Action.CLOSE;
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
                PaymentAttempt.Action action = transaction.afterMissing(attempt.orderId(), attempt.token());
                remaining = budget.minusNanos(Math.max(0, System.nanoTime() - started));
                if (remaining.isNegative() || remaining.isZero()) {
                    result = GatewayResult.UNKNOWN;
                } else {
                    expiryCloseAttempted = action == PaymentAttempt.Action.CLOSE;
                    result = switch (action) {
                        case CHARGE -> gateway.charge(attempt.payment(), remaining);
                        case CLOSE -> gateway.close(attempt.payment(), remaining);
                        default -> GatewayResult.UNKNOWN;
                    };
                }
            } else {
                result = GatewayResult.UNKNOWN;
            }
        }
        transaction.record(attempt.orderId(), attempt.token(), result);
        return transaction.finish(attempt.orderId(), attempt.token(), expiryCloseAttempted);
    }
}
