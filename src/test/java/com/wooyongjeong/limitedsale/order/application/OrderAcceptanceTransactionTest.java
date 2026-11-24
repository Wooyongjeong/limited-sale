package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.application.OrderAcceptanceResult.Kind;
import com.wooyongjeong.limitedsale.order.domain.AcceptanceMessage;
import com.wooyongjeong.limitedsale.order.domain.AcceptanceStatus;
import com.wooyongjeong.limitedsale.order.domain.OrderAcceptance;
import com.wooyongjeong.limitedsale.order.domain.OrderAcceptanceStore;
import com.wooyongjeong.limitedsale.order.domain.OrderRejection;
import com.wooyongjeong.limitedsale.sale.api.SaleAdmission;
import com.wooyongjeong.limitedsale.sale.api.SaleAdmissionResult;
import com.wooyongjeong.limitedsale.sale.api.SaleAdmissionResult.Reason;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderAcceptanceTransactionTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00.123456789Z");
    private static final Instant DB_TIME = Instant.parse("2026-01-01T00:00:00.654321Z");
    private final OrderAcceptanceStore store = mock(OrderAcceptanceStore.class);
    private final SaleAdmission sales = mock(SaleAdmission.class);
    private final OrderAcceptanceTransaction transaction = new OrderAcceptanceTransaction(store, sales,
        Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID key = UUID.randomUUID();

    private static OrderAcceptance stored(AcceptanceStatus status, OrderRejection rejection) {
        return new OrderAcceptance(UUID.randomUUID(), 2, 3, status, rejection, null, null, DB_TIME, null);
    }

    @Test
    void anAdmittedRequestStoresTheAcceptanceAndTheOutboxMessageAtTheDatabaseTime() {
        when(store.request(1, key)).thenReturn(Optional.empty());
        when(sales.check(2)).thenReturn(new SaleAdmissionResult(Reason.ADMITTED, DB_TIME));
        when(store.accept(any(), eq(1L), eq(key), eq(2L), eq(3), eq(DB_TIME), any()))
            .thenAnswer(call -> new OrderAcceptance(call.getArgument(0), 2, 3, AcceptanceStatus.ACCEPTED, null, null,
                null, DB_TIME, null));

        var result = transaction.accept(1, key, 2, 3);

        assertThat(result.kind()).isEqualTo(Kind.ACCEPTED);
        var message = ArgumentCaptor.forClass(AcceptanceMessage.class);
        verify(store).accept(eq(result.acceptance().acceptanceId()), eq(1L), eq(key), eq(2L), eq(3), eq(DB_TIME),
            message.capture());
        assertThat(message.getValue()).isEqualTo(AcceptanceMessage.of("limited-sale.order-acceptances.v1",
            result.acceptance().acceptanceId(), 2));
    }

    @ParameterizedTest
    @CsvSource({"SALE_NOT_FOUND,SALE_NOT_FOUND", "SALE_OUTSIDE_PERIOD,SALE_OUTSIDE_PERIOD", "SALE_STOPPED,SALE_STOPPED"})
    void aRejectedRequestIsStoredWithItsReasonAndNoOutboxMessage(Reason reason, Kind kind) {
        when(store.request(1, key)).thenReturn(Optional.empty());
        when(sales.check(2)).thenReturn(new SaleAdmissionResult(reason, DB_TIME));

        var result = transaction.accept(1, key, 2, 3);

        assertThat(result).isEqualTo(new OrderAcceptanceResult(kind, null));
        verify(store).reject(any(), eq(1L), eq(key), eq(2L), eq(3), eq(OrderRejection.valueOf(reason.name())),
            eq(DB_TIME));
        verify(store, never()).accept(any(), any(Long.class), any(), any(Long.class), any(Integer.class), any(),
            any());
    }

    @Test
    void anExistingKeyIsReplayedBeforeTheSaleIsRead() {
        var existing = stored(AcceptanceStatus.ACCEPTED, null);
        when(store.request(1, key)).thenReturn(Optional.of(existing));

        assertThat(transaction.accept(1, key, 2, 3)).isEqualTo(new OrderAcceptanceResult(Kind.REPLAYED, existing));

        verifyNoInteractions(sales);
    }

    @Test
    void aStoredRejectionIsRepeatedWithTheSameKindAndADifferentInputIsAConflict() {
        when(store.request(1, key)).thenReturn(Optional.of(stored(AcceptanceStatus.REJECTED,
            OrderRejection.SALE_STOPPED)));

        assertThat(transaction.accept(1, key, 2, 3).kind()).isEqualTo(Kind.SALE_STOPPED);
        assertThat(transaction.accept(1, key, 2, 4).kind()).isEqualTo(Kind.IDEMPOTENCY_CONFLICT);
        assertThat(transaction.accept(1, key, 9, 3).kind()).isEqualTo(Kind.IDEMPOTENCY_CONFLICT);

        verifyNoInteractions(sales);
    }

    @Test
    void everyNonRejectedStatusReplaysWithItsCurrentStatus() {
        for (var status : new AcceptanceStatus[] {AcceptanceStatus.ACCEPTED, AcceptanceStatus.ORDER_CREATED,
                AcceptanceStatus.PROCESSING_FAILED}) {
            var existing = stored(status, null);
            when(store.request(1, key)).thenReturn(Optional.of(existing));

            assertThat(transaction.lookupRequest(1, key, 2, 3)).contains(new OrderAcceptanceResult(Kind.REPLAYED,
                existing));
        }
    }

    @Test
    void aMarkedSaleStoresASoldOutRejectionWithoutReadingTheSaleOrWritingTheOutbox() {
        when(store.request(1, key)).thenReturn(Optional.empty());

        var result = transaction.rejectSoldOut(1, key, 2, 3);

        assertThat(result.kind()).isEqualTo(Kind.SALE_SOLD_OUT);
        verify(store).reject(any(), eq(1L), eq(key), eq(2L), eq(3), eq(OrderRejection.SALE_SOLD_OUT),
            eq(Instant.parse("2026-01-01T00:00:00.123456Z")));
        verifyNoInteractions(sales);
    }

    @Test
    void aMarkedSaleStillReplaysAnExistingKeyFirst() {
        var existing = stored(AcceptanceStatus.ACCEPTED, null);
        when(store.request(1, key)).thenReturn(Optional.of(existing));

        assertThat(transaction.rejectSoldOut(1, key, 2, 3).kind()).isEqualTo(Kind.REPLAYED);

        verify(store, never()).reject(any(), any(Long.class), any(), any(Long.class), any(Integer.class), any(),
            any());
    }

    @Test
    void findReturnsTheOwnersAcceptanceOrNotFound() {
        var mine = stored(AcceptanceStatus.ACCEPTED, null);
        when(store.find(1, mine.acceptanceId())).thenReturn(Optional.of(mine));

        assertThat(transaction.find(1, mine.acceptanceId()).acceptance()).isEqualTo(mine);
        assertThat(transaction.find(2, mine.acceptanceId()).kind()).isEqualTo(Kind.ACCEPTANCE_NOT_FOUND);
    }
}
