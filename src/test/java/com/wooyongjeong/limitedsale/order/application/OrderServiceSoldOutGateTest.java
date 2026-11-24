package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.OrderRateLimit;
import com.wooyongjeong.limitedsale.order.domain.OrderRequestKeyCollision;
import com.wooyongjeong.limitedsale.sale.api.SoldOutGate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderServiceSoldOutGateTest {
    private final OrderTransaction transaction = mock(OrderTransaction.class);
    private final SoldOutGate gate = mock(SoldOutGate.class);
    private final OrderRateLimit limit = mock(OrderRateLimit.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OrderMetrics metrics = new OrderMetrics(registry);
    private final OrderService service = new OrderService(transaction, metrics,
        new OrderRateLimiter(limit, metrics, false), gate);
    private final UUID key = UUID.randomUUID();

    @Test
    void aMarkedSaleGoesToTheRejectionTransactionAndNeverToTheLockingOne() {
        when(gate.isMarked(5)).thenReturn(true);
        when(transaction.rejectSoldOut(1, key, 5, 1)).thenReturn(new OrderResult(OrderResult.Kind.SALE_SOLD_OUT, null));

        var result = service.create(1, key, 5, 1);

        assertThat(result.kind()).isEqualTo(OrderResult.Kind.SALE_SOLD_OUT);
        verify(transaction, never()).create(any(Long.class), any(), any(Long.class), any(Integer.class));
        assertThat(registry.get("order.creation.results").tag("result", "sale_sold_out").counter().count())
            .isEqualTo(1);
    }

    @Test
    void anUnmarkedSaleTakesTheExistingTransaction() {
        when(gate.isMarked(5)).thenReturn(false);
        when(transaction.create(1, key, 5, 1)).thenReturn(new OrderResult(OrderResult.Kind.CREATED, null));

        assertThat(service.create(1, key, 5, 1).kind()).isEqualTo(OrderResult.Kind.CREATED);

        verify(transaction, never()).rejectSoldOut(any(Long.class), any(), any(Long.class), any(Integer.class));
    }

    @Test
    void theGateIsReadBeforeAnyTransactionStarts() {
        when(gate.isMarked(5)).thenReturn(false);
        when(transaction.create(1, key, 5, 1)).thenReturn(new OrderResult(OrderResult.Kind.CREATED, null));

        service.create(1, key, 5, 1);

        InOrder order = inOrder(gate, transaction);
        order.verify(gate).isMarked(5);
        order.verify(transaction).create(1, key, 5, 1);
    }

    @Test
    void aKeyCollisionOnTheRejectionPathReplaysTheStoredResult() {
        when(gate.isMarked(5)).thenReturn(true);
        var collision = new OrderRequestKeyCollision(new IllegalStateException());
        when(transaction.rejectSoldOut(1, key, 5, 1)).thenThrow(collision);
        when(transaction.lookupRequest(1, key, 5, 1))
            .thenReturn(Optional.of(new OrderResult(OrderResult.Kind.REPLAYED, null)));

        assertThat(service.create(1, key, 5, 1).kind()).isEqualTo(OrderResult.Kind.REPLAYED);
    }

    @Test
    void aRateLimitedRequestNeverReachesTheGate() {
        var limited = new OrderService(transaction, metrics, new OrderRateLimiter(limit, metrics, true), gate);
        when(limit.hit(1, OrderRateLimit.Operation.CREATE))
            .thenReturn(new OrderRateLimit.Decision.Rejected(java.time.Duration.ofMillis(200)));

        assertThat(limited.create(1, key, 5, 1).kind()).isEqualTo(OrderResult.Kind.RATE_LIMITED);

        verifyNoInteractions(gate, transaction);
    }
}
