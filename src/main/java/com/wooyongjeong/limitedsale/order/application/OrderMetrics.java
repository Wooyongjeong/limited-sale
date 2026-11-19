package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.application.OrderResult.Kind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Counts the business outcome of order creation and payment requests. The only label is the outcome kind,
 * so member, order and request identifiers never reach the metric series.
 */
@Component
public class OrderMetrics {
    private static final String CREATION = "order.creation.results";
    private static final String PAYMENT = "order.payment.results";
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
    }

    public void creation(Kind kind) {
        counter(CREATION, "Order creation requests by outcome", kind).increment();
    }

    public void payment(Kind kind) {
        counter(PAYMENT, "Order payment requests by outcome", kind).increment();
    }

    private Counter counter(String name, String description, Kind kind) {
        return Counter.builder(name).description(description).tag("result", kind.name().toLowerCase(Locale.ROOT))
            .register(registry);
    }
}
