package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.OrderRejection;
import com.wooyongjeong.limitedsale.order.domain.OrderRequestOutcome;
import com.wooyongjeong.limitedsale.order.domain.OrderStore;
import com.wooyongjeong.limitedsale.payment.api.PaymentPreparation;
import com.wooyongjeong.limitedsale.sale.api.SaleAllocation;
import com.wooyongjeong.limitedsale.sale.api.SaleReservation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class OrderTransactionTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00.123456789Z");
    private final OrderStore store = mock(OrderStore.class);
    private final SaleReservation sale = mock(SaleReservation.class);
    private final PaymentPreparation payment = mock(PaymentPreparation.class);
    private final OrderTransaction transaction = new OrderTransaction(store, sale, payment, Duration.ofSeconds(42),
        Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void configuredExpirationIsAppliedToDatabaseDecisionTime() {
        UUID key = UUID.randomUUID();
        Instant now = Instant.parse("2026-01-01T00:00:00.123456Z");
        when(store.request(1, key)).thenReturn(Optional.empty());
        when(sale.allocate(2, 1, 3)).thenReturn(new SaleAllocation(SaleAllocation.Reason.ALLOCATED,
            4, "product", 100, now));
        when(store.create(eq(1L), eq(key), eq(2L), eq(4L), eq("product"), eq(100L), eq(3),
            eq(now), any())).thenReturn(5L);
        var result = transaction.create(1, key, 2, 3);
        assertThat(result.kind()).isEqualTo(OrderResult.Kind.CREATED);
        verify(store).create(1, key, 2, 4, "product", 100, 3, now, now.plusSeconds(42));
        verify(payment).prepare(5, 300, now);
    }

    @Test
    void aNewKeyOnAMarkedSaleStoresASoldOutRejectionWithoutTouchingTheSale() {
        UUID key = UUID.randomUUID();
        when(store.request(1, key)).thenReturn(Optional.empty());

        var result = transaction.rejectSoldOut(1, key, 2, 3);

        assertThat(result).isEqualTo(new OrderResult(OrderResult.Kind.SALE_SOLD_OUT, null));
        verify(store).record(1, key, 2, 3, null, OrderRejection.SALE_SOLD_OUT,
            Instant.parse("2026-01-01T00:00:00.123456Z"));
        verifyNoInteractions(sale, payment);
    }

    @Test
    void anExistingKeyIsReplayedBeforeTheMarkerCanReject() {
        UUID key = UUID.randomUUID();
        when(store.request(1, key)).thenReturn(Optional.of(new OrderRequestOutcome(2, 3, 9L, null)));

        var result = transaction.rejectSoldOut(1, key, 2, 3);

        assertThat(result.kind()).isEqualTo(OrderResult.Kind.REPLAYED);
        verify(store).find(9L);
        verify(store, never()).record(any(Long.class), any(), any(Long.class), any(Integer.class), any(), any(),
            any());
        verifyNoInteractions(sale, payment);
    }

    @Test
    void anExistingRejectionIsRepeatedAndADifferentRequestIsAConflict() {
        UUID key = UUID.randomUUID();
        when(store.request(1, key)).thenReturn(
            Optional.of(new OrderRequestOutcome(2, 3, null, OrderRejection.PURCHASE_LIMIT_EXCEEDED)));

        assertThat(transaction.rejectSoldOut(1, key, 2, 3).kind())
            .isEqualTo(OrderResult.Kind.PURCHASE_LIMIT_EXCEEDED);
        assertThat(transaction.rejectSoldOut(1, key, 2, 4).kind())
            .isEqualTo(OrderResult.Kind.IDEMPOTENCY_CONFLICT);

        verify(store, never()).record(any(Long.class), any(), any(Long.class), any(Integer.class), any(), any(),
            any());
        verifyNoInteractions(sale, payment);
        verifyNoMoreInteractions(sale);
    }
}
