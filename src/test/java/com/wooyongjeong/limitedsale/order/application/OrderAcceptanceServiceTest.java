package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.application.OrderAcceptanceResult.Kind;
import com.wooyongjeong.limitedsale.order.domain.OrderRateLimit;
import com.wooyongjeong.limitedsale.order.domain.OrderRequestKeyCollision;
import com.wooyongjeong.limitedsale.sale.api.SoldOutGate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderAcceptanceServiceTest {
    private final OrderAcceptanceTransaction transaction = mock(OrderAcceptanceTransaction.class);
    private final SoldOutGate gate = mock(SoldOutGate.class);
    private final OrderRateLimit limit = mock(OrderRateLimit.class);
    private final OrderMetrics metrics = new OrderMetrics(new SimpleMeterRegistry());
    private final OrderAcceptanceService service = new OrderAcceptanceService(transaction,
        new OrderRateLimiter(limit, metrics, true), gate);
    private final UUID key = UUID.randomUUID();

    @Test
    void aRateLimitedRequestNeverReachesTheGateOrTheDatabase() {
        when(limit.hit(1, OrderRateLimit.Operation.CREATE))
            .thenReturn(new OrderRateLimit.Decision.Rejected(Duration.ofMillis(200)));

        var result = service.accept(1, key, 5, 1);

        assertThat(result).isEqualTo(OrderAcceptanceResult.rateLimited(Duration.ofMillis(200)));
        verifyNoInteractions(gate, transaction);
    }

    @Test
    void theGateIsReadBeforeAnyTransactionAndAMarkedSaleTakesTheRejectionPath() {
        when(limit.hit(1, OrderRateLimit.Operation.CREATE)).thenReturn(new OrderRateLimit.Decision.Allowed());
        when(gate.isMarked(5)).thenReturn(true);
        when(transaction.rejectSoldOut(1, key, 5, 1)).thenReturn(new OrderAcceptanceResult(Kind.SALE_SOLD_OUT, null));

        assertThat(service.accept(1, key, 5, 1).kind()).isEqualTo(Kind.SALE_SOLD_OUT);

        InOrder order = inOrder(gate, transaction);
        order.verify(gate).isMarked(5);
        order.verify(transaction).rejectSoldOut(1, key, 5, 1);
        verify(transaction, never()).accept(1, key, 5, 1);
    }

    @Test
    void anUnmarkedSaleIsAccepted() {
        when(limit.hit(1, OrderRateLimit.Operation.CREATE)).thenReturn(new OrderRateLimit.Decision.Allowed());
        when(gate.isMarked(5)).thenReturn(false);
        when(transaction.accept(1, key, 5, 1)).thenReturn(new OrderAcceptanceResult(Kind.ACCEPTED, null));

        assertThat(service.accept(1, key, 5, 1).kind()).isEqualTo(Kind.ACCEPTED);
    }

    @Test
    void aKeyCollisionAnswersWithTheWinnersStoredResult() {
        when(limit.hit(1, OrderRateLimit.Operation.CREATE)).thenReturn(new OrderRateLimit.Decision.Allowed());
        when(transaction.accept(1, key, 5, 1)).thenThrow(new OrderRequestKeyCollision(new IllegalStateException()));
        when(transaction.lookupRequest(1, key, 5, 1))
            .thenReturn(Optional.of(new OrderAcceptanceResult(Kind.REPLAYED, null)));

        assertThat(service.accept(1, key, 5, 1).kind()).isEqualTo(Kind.REPLAYED);
    }

    @Test
    void aCollisionWithNoWinnerToReadIsAFailure() {
        when(limit.hit(1, OrderRateLimit.Operation.CREATE)).thenReturn(new OrderRateLimit.Decision.Allowed());
        when(transaction.accept(1, key, 5, 1)).thenThrow(new OrderRequestKeyCollision(new IllegalStateException()));
        when(transaction.lookupRequest(1, key, 5, 1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.accept(1, key, 5, 1)).isInstanceOf(OrderRequestKeyCollision.class);
    }
}
