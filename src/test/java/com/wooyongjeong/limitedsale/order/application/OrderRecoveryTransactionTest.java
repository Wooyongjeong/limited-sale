package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.OrderStore;
import com.wooyongjeong.limitedsale.order.domain.PaymentOrder;
import com.wooyongjeong.limitedsale.order.domain.PaymentOrderStore;
import com.wooyongjeong.limitedsale.payment.api.GatewayResult;
import com.wooyongjeong.limitedsale.payment.api.PaymentDetails;
import com.wooyongjeong.limitedsale.payment.api.PaymentOperations;
import com.wooyongjeong.limitedsale.sale.api.SaleReservation;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderRecoveryTransactionTest {
    private final PaymentOrderStore orders = mock(PaymentOrderStore.class);
    private final OrderStore views = mock(OrderStore.class);
    private final PaymentOperations payments = mock(PaymentOperations.class);
    private final SaleReservation sales = mock(SaleReservation.class);
    private final OrderPaymentTransaction transaction = new OrderPaymentTransaction(orders, views, payments, sales,
            Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(5));
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void beginSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void expiredOwnerCannotRecordOrReleaseTheNewOwnerLease() {
        UUID old = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        PaymentOrder order = order(current, now.plusSeconds(30), now.minusSeconds(1));
        when(orders.lock(1)).thenReturn(order);
        when(orders.databaseTime()).thenReturn(now);
        assertThat(transaction.record(1, old, GatewayResult.SUCCEEDED)).isFalse();
        transaction.finish(1, old, false);
        verify(payments, never()).record(anyLong(), any(), any());
        verify(orders, never()).release(anyLong());
        verify(orders, never()).reschedule(anyLong(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
        verify(sales, never()).finish(anyLong(), anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void recoveryDoesNotStartPaymentForReadyOrderBeforeExpiry() {
        PaymentOrder order = order(null, null, now.plusSeconds(60));
        when(orders.tryLock(1)).thenReturn(Optional.of(order));
        when(orders.databaseTime()).thenReturn(now);
        when(payments.lock(1)).thenReturn(new PaymentDetails(UUID.randomUUID(), 1000, "READY"));
        assertThat(transaction.beginRecovery(1).action()).isEqualTo(PaymentAttempt.Action.WAIT);
        verify(payments, never()).markStarted(anyLong(), any());
        verify(orders, never()).claim(anyLong(), any(), any());
    }

    @Test
    void unresolvedExpiredPaymentKeepsReservationAndSchedulesReview() {
        UUID token = UUID.randomUUID();
        PaymentOrder order = order(token, now.plusSeconds(30), now.minusSeconds(1));
        when(orders.lock(1)).thenReturn(order);
        when(orders.databaseTime()).thenReturn(now);
        when(payments.lock(1)).thenReturn(new PaymentDetails(UUID.randomUUID(), 1000, "UNKNOWN"));
        transaction.finish(1, token, true);
        verify(orders).reschedule(1, now.plusSeconds(10), true, now.plusSeconds(300));
        verify(orders).release(1);
        verify(sales, never()).finish(anyLong(), anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void expiredLeaseCannotRecordEvenIfTokenWasNotReplaced() {
        UUID token = UUID.randomUUID();
        when(orders.lock(1)).thenReturn(order(token, now, now.plusSeconds(60)));
        when(orders.databaseTime()).thenReturn(now);
        assertThat(transaction.record(1, token, GatewayResult.SUCCEEDED)).isFalse();
        verify(payments, never()).record(eq(1L), any(), any());
    }

    @Test
    void leaseExpiringDuringResultRecordingAbortsTheTransaction() {
        UUID token = UUID.randomUUID();
        when(orders.lock(1)).thenReturn(order(token, now.plusSeconds(30), now.plusSeconds(60)));
        when(orders.databaseTime()).thenReturn(now, now.plusSeconds(31));
        assertThatThrownBy(() -> transaction.record(1, token, GatewayResult.SUCCEEDED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void repeatedReviewRetainsWarningDeadlineUntilItIsDue() {
        UUID token = UUID.randomUUID();
        Instant warning = now.plusSeconds(200);
        var order = new PaymentOrder(1, UUID.randomUUID(), 2, 3, 1, "PAYMENT_PENDING", now.minusSeconds(1),
                now.minusSeconds(1), token, now.plusSeconds(30), true, warning);
        when(orders.lock(1)).thenReturn(order);
        when(orders.databaseTime()).thenReturn(now);
        when(payments.lock(1)).thenReturn(new PaymentDetails(UUID.randomUUID(), 1000, "UNKNOWN"));
        transaction.finish(1, token, true);
        verify(orders).reschedule(1, now.plusSeconds(10), true, warning);
    }

    @Test
    void expiryBypassesAnEarlierRetryDeadline() {
        var order = new PaymentOrder(1, UUID.randomUUID(), 2, 3, 1, "PAYMENT_PENDING", now.minusSeconds(1),
                now.plusSeconds(8), null, null, false, null);
        when(orders.tryLock(1)).thenReturn(Optional.of(order));
        when(orders.databaseTime()).thenReturn(now);
        when(payments.lock(1)).thenReturn(new PaymentDetails(UUID.randomUUID(), 1000, "UNKNOWN"));
        assertThat(transaction.beginRecovery(1).action()).isEqualTo(PaymentAttempt.Action.CLOSE);
    }

    @Test
    void reachingExpiryDuringAnEarlierRequestSchedulesImmediateClose() {
        UUID token = UUID.randomUUID();
        PaymentOrder order = order(token, now.plusSeconds(30), now.minusSeconds(1));
        when(orders.lock(1)).thenReturn(order);
        when(orders.databaseTime()).thenReturn(now);
        when(payments.lock(1)).thenReturn(new PaymentDetails(UUID.randomUUID(), 1000, "UNKNOWN"));
        transaction.finish(1, token, false);
        verify(orders).reschedule(1, now, true, now.plusSeconds(300));
    }

    private PaymentOrder order(UUID token, Instant until, Instant expires) {
        return new PaymentOrder(1, UUID.randomUUID(), 2, 3, 1, "PAYMENT_PENDING", expires,
                now.minusSeconds(1), token, until, false, null);
    }
}
