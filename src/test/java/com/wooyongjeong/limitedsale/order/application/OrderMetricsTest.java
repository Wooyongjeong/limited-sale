package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.OrderBacklog;
import com.wooyongjeong.limitedsale.order.domain.OrderRequestKeyCollision;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OrderMetrics metrics = new OrderMetrics(registry);

    private double count(String name, String result) {
        return registry.get(name).tag("result", result).counter().count();
    }

    @Test
    void everyOutcomeStartsAtZeroSoRatesHaveABaseline() {
        for (String result : new String[] {"created", "replayed", "sale_not_found", "sale_outside_period",
            "sale_stopped", "purchase_limit_exceeded", "sale_sold_out", "idempotency_conflict"}) {
            assertThat(count("order.creation.results", result)).isZero();
        }
        for (String result : new String[] {"payment_succeeded", "payment_pending", "payment_declined",
            "order_expired", "order_not_found"}) {
            assertThat(count("order.payment.results", result)).isZero();
        }
    }

    @Test
    void metricsCarryOnlyTheResultLabel() {
        metrics.creation(OrderResult.Kind.SALE_SOLD_OUT);
        metrics.payment(OrderResult.Kind.PAYMENT_DECLINED);
        for (Meter meter : registry.getMeters()) {
            assertThat(meter.getId().getTags()).extracting(tag -> tag.getKey()).containsExactly("result");
        }
    }

    @Test
    void createResultsAreCountedIncludingTheKeyCollisionFallback() {
        var transaction = mock(OrderTransaction.class);
        var key = UUID.randomUUID();
        var collision = new OrderRequestKeyCollision(new IllegalStateException());
        when(transaction.create(1, key, 5, 1)).thenReturn(new OrderResult(OrderResult.Kind.SALE_SOLD_OUT, null))
            .thenThrow(collision);
        when(transaction.lookupRequest(1, key, 5, 1))
            .thenReturn(Optional.of(new OrderResult(OrderResult.Kind.REPLAYED, null)));
        var service = new OrderService(transaction, metrics);
        service.create(1, key, 5, 1);
        service.create(1, key, 5, 1);
        assertThat(count("order.creation.results", "sale_sold_out")).isEqualTo(1);
        assertThat(count("order.creation.results", "replayed")).isEqualTo(1);
        assertThat(count("order.creation.results", "created")).isZero();
    }

    @Test
    void gaugesAreUnknownUntilCountedAndNeverShowAStaleValueAfterAFailure() {
        var backlog = mock(OrderBacklog.class);
        var gauges = new OrderBacklogGauges(backlog, registry);
        assertThat(registry.get("order.unfinished").gauge().value()).isNaN();
        when(backlog.unfinished()).thenReturn(7L);
        when(backlog.requiringReview()).thenReturn(2L);
        gauges.refresh();
        assertThat(registry.get("order.unfinished").gauge().value()).isEqualTo(7);
        assertThat(registry.get("order.requires.review").gauge().value()).isEqualTo(2);
        when(backlog.unfinished()).thenThrow(new IllegalStateException("db down"));
        gauges.refresh();
        assertThat(registry.get("order.unfinished").gauge().value()).isNaN();
        assertThat(registry.get("order.requires.review").gauge().value()).isNaN();
    }
}
