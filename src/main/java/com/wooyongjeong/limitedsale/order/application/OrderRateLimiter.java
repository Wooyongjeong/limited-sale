package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.application.OrderMetrics.RateLimitResult;
import com.wooyongjeong.limitedsale.order.domain.OrderRateLimit;
import com.wooyongjeong.limitedsale.order.domain.OrderRateLimit.Decision;
import com.wooyongjeong.limitedsale.order.domain.OrderRateLimit.Operation;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Applies the per-member request limit at the start of order creation and payment, before any request key lookup
 * or database transaction. When the limit is switched off Redis is not called at all. When Redis fails the request
 * is let through and counted as bypassed: MySQL still protects stock and purchase limits, so the limit is a
 * protection that may degrade, not a rule.
 */
@Component
public class OrderRateLimiter {
    private final OrderRateLimit limit;
    private final OrderMetrics metrics;
    private final boolean enabled;

    public OrderRateLimiter(OrderRateLimit limit, OrderMetrics metrics,
            @Value("${limited-sale.order.rate-limit.enabled:true}") boolean enabled) {
        this.limit = limit;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    /** Returns the rejection to answer with, or empty when the request may proceed. */
    public Optional<OrderResult> reject(long memberId, Operation operation) {
        if (!enabled) {
            return Optional.empty();
        }
        return switch (limit.hit(memberId, operation)) {
            case Decision.Allowed allowed -> {
                metrics.rateLimit(operation, RateLimitResult.ALLOWED);
                yield Optional.empty();
            }
            case Decision.Unavailable unavailable -> {
                metrics.rateLimit(operation, RateLimitResult.BYPASSED);
                yield Optional.empty();
            }
            case Decision.Rejected rejected -> {
                metrics.rateLimit(operation, RateLimitResult.REJECTED);
                yield Optional.of(OrderResult.rateLimited(rejected.retryAfter()));
            }
        };
    }
}
