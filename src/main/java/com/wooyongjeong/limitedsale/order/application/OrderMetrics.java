package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.application.OrderResult.Kind;
import com.wooyongjeong.limitedsale.order.domain.OrderRateLimit.Operation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Counts the business outcome of order creation and payment requests. The labels are the outcome kind, plus the
 * operation for the request limit, so member, order and request identifiers never reach the metric series.
 */
@Component
public class OrderMetrics {
    private static final String CREATION = "order.creation.results";
    private static final String PAYMENT = "order.payment.results";
    private static final String RATE_LIMIT = "order.rate.limit";
    private static final Set<Kind> CREATION_KINDS = EnumSet.of(Kind.CREATED, Kind.REPLAYED, Kind.SALE_NOT_FOUND,
        Kind.SALE_OUTSIDE_PERIOD, Kind.SALE_STOPPED, Kind.PURCHASE_LIMIT_EXCEEDED, Kind.SALE_SOLD_OUT,
        Kind.IDEMPOTENCY_CONFLICT);
    private static final Set<Kind> PAYMENT_KINDS = EnumSet.of(Kind.PAYMENT_SUCCEEDED, Kind.PAYMENT_PENDING,
        Kind.PAYMENT_DECLINED, Kind.ORDER_EXPIRED, Kind.ORDER_NOT_FOUND);
    private final MeterRegistry registry;

    public OrderMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Register every outcome up front so rate() and increase() see a zero baseline.
        CREATION_KINDS.forEach(kind -> counter(CREATION, "Order creation requests by outcome", kind));
        PAYMENT_KINDS.forEach(kind -> counter(PAYMENT, "Order payment requests by outcome", kind));
        for (Operation operation : Operation.values()) {
            for (RateLimitResult result : RateLimitResult.values()) {
                rateLimitCounter(operation, result);
            }
        }
    }

    /** {@code BYPASSED} is a request let through because Redis could not answer. */
    public enum RateLimitResult {
        ALLOWED,
        REJECTED,
        BYPASSED
    }

    public void creation(Kind kind) {
        counter(CREATION, "Order creation requests by outcome", kind).increment();
    }

    public void payment(Kind kind) {
        counter(PAYMENT, "Order payment requests by outcome", kind).increment();
    }

    public void rateLimit(Operation operation, RateLimitResult result) {
        rateLimitCounter(operation, result).increment();
    }

    private Counter rateLimitCounter(Operation operation, RateLimitResult result) {
        return Counter.builder(RATE_LIMIT).description("Order request limit decisions by operation and result")
            .tag("operation", operation.name().toLowerCase(Locale.ROOT))
            .tag("result", result.name().toLowerCase(Locale.ROOT)).register(registry);
    }

    private Counter counter(String name, String description, Kind kind) {
        return Counter.builder(name).description(description).tag("result", kind.name().toLowerCase(Locale.ROOT))
            .register(registry);
    }
}
