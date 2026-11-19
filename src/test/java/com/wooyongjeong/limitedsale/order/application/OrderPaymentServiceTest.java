package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.payment.api.GatewayResult;
import com.wooyongjeong.limitedsale.payment.api.PaymentDetails;
import com.wooyongjeong.limitedsale.payment.api.PaymentGateway;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderPaymentServiceTest {
    private static OrderMetrics metrics() {
        return new OrderMetrics(new SimpleMeterRegistry());
    }

    private static OrderPaymentTransaction transaction() {
        var transaction = mock(OrderPaymentTransaction.class);
        when(transaction.finish(anyLong(), any(), anyBoolean()))
                .thenReturn(new OrderResult(OrderResult.Kind.PAYMENT_PENDING, null));
        return transaction;
    }

    @Test
    void theUserVisibleResultIsCountedOncePerPaymentRequestButNotPerRecovery() {
        var token = UUID.randomUUID();
        var transaction = transaction();
        var gateway = mock(PaymentGateway.class);
        var id = UUID.randomUUID();
        var payment = new PaymentDetails(UUID.randomUUID(), 1000, "READY");
        when(transaction.begin(1, id)).thenReturn(new PaymentAttempt(7, payment, PaymentAttempt.Action.CHARGE, token));
        when(transaction.beginRecovery(7)).thenReturn(new PaymentAttempt(7, payment, PaymentAttempt.Action.QUERY, token));
        when(transaction.finish(7, token, false)).thenReturn(new OrderResult(OrderResult.Kind.PAYMENT_DECLINED, null));
        when(gateway.charge(any(), any())).thenReturn(GatewayResult.FAILED);
        when(gateway.query(any(), any())).thenReturn(GatewayResult.FAILED);
        var registry = new SimpleMeterRegistry();
        var service = new OrderPaymentService(transaction, gateway, new OrderMetrics(registry), Duration.ofSeconds(1));
        service.pay(1, id);
        service.recover(7);
        assertThat(registry.get("order.payment.results").tag("result", "payment_declined").counter().count())
                .isEqualTo(1);
    }

    @Test
    void aMissingPaymentIsClosedIfTheOrderExpiredDuringTheQuery() {
        var idToken = UUID.randomUUID();
        var transaction = transaction();
        var gateway = mock(PaymentGateway.class);
        var id = UUID.randomUUID();
        var payment = new PaymentDetails(UUID.randomUUID(), 1000, "PROCESSING");
        when(transaction.begin(1, id)).thenReturn(new PaymentAttempt(7, payment, PaymentAttempt.Action.QUERY, idToken));
        when(gateway.query(any(), any())).thenReturn(GatewayResult.MISSING);
        when(transaction.afterMissing(7, idToken)).thenReturn(PaymentAttempt.Action.CLOSE);
        when(gateway.close(any(), any())).thenReturn(GatewayResult.CLOSED);
        new OrderPaymentService(transaction, gateway, metrics(), Duration.ofSeconds(1)).pay(1, id);
        verify(gateway, never()).charge(any(), any());
        verify(transaction).record(7, idToken, GatewayResult.CLOSED);
    }

    @Test
    void anExhaustedBudgetPreventsASecondCall() {
        var idToken = UUID.randomUUID();
        var transaction = transaction();
        var gateway = mock(PaymentGateway.class);
        var id = UUID.randomUUID();
        var payment = new PaymentDetails(UUID.randomUUID(), 1000, "PROCESSING");
        when(transaction.begin(1, id)).thenReturn(new PaymentAttempt(7, payment, PaymentAttempt.Action.QUERY, idToken));
        when(gateway.query(any(), any())).thenAnswer(call -> {
            Thread.sleep(100);
            return GatewayResult.MISSING;
        });
        new OrderPaymentService(transaction, gateway, metrics(), Duration.ofMillis(20)).pay(1, id);
        verify(gateway, never()).charge(any(), any());
        verify(gateway, never()).close(any(), any());
        verify(transaction).record(7, idToken, GatewayResult.UNKNOWN);
    }

    @Test
    void queryAndChargeShareTheCommunicationBudget() {
        var idToken = UUID.randomUUID();
        var transaction = transaction();
        var gateway = mock(PaymentGateway.class);
        var orderId = UUID.randomUUID();
        var payment = new PaymentDetails(UUID.randomUUID(), 1000, "PROCESSING");
        when(transaction.begin(1, orderId)).thenReturn(new PaymentAttempt(7, payment, PaymentAttempt.Action.QUERY, idToken));
        when(gateway.query(any(), any())).thenAnswer(call -> {
            Thread.sleep(100);
            return GatewayResult.MISSING;
        });
        when(gateway.charge(any(), any())).thenReturn(GatewayResult.SUCCEEDED);
        when(transaction.afterMissing(7, idToken)).thenReturn(PaymentAttempt.Action.CHARGE);
        var service = new OrderPaymentService(transaction, gateway, metrics(), Duration.ofSeconds(1));
        service.pay(1, orderId);
        var remaining = ArgumentCaptor.forClass(Duration.class);
        verify(gateway).charge(any(), remaining.capture());
        assertThat(remaining.getValue()).isPositive().isLessThan(Duration.ofMillis(950));
        verify(transaction).record(7, idToken, GatewayResult.SUCCEEDED);
    }

    @Test
    void databaseDelayConsumesTheSharedCommunicationBudget() {
        var token = UUID.randomUUID();
        var transaction = transaction();
        var gateway = mock(PaymentGateway.class);
        var id = UUID.randomUUID();
        var payment = new PaymentDetails(UUID.randomUUID(), 1000, "PROCESSING");
        when(transaction.begin(1, id)).thenReturn(new PaymentAttempt(7, payment, PaymentAttempt.Action.QUERY, token));
        when(gateway.query(any(), any())).thenReturn(GatewayResult.MISSING);
        when(transaction.afterMissing(7, token)).thenAnswer(call -> {
            Thread.sleep(100);
            return PaymentAttempt.Action.CHARGE;
        });
        new OrderPaymentService(transaction, gateway, metrics(), Duration.ofMillis(20)).pay(1, id);
        verify(gateway, never()).charge(any(), any());
        verify(gateway, never()).close(any(), any());
    }

}
