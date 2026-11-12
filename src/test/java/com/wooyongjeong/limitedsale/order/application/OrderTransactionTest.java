package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.OrderStore;
import com.wooyongjeong.limitedsale.payment.api.PaymentPreparation;
import com.wooyongjeong.limitedsale.sale.api.SaleAllocation;
import com.wooyongjeong.limitedsale.sale.api.SaleReservation;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderTransactionTest {
    @Test
    void configuredExpirationIsAppliedToDatabaseDecisionTime() {
        OrderStore store = mock(OrderStore.class);
        SaleReservation sale = mock(SaleReservation.class);
        PaymentPreparation payment = mock(PaymentPreparation.class);
        var transaction = new OrderTransaction(store, sale, payment, Duration.ofSeconds(42));
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
}
