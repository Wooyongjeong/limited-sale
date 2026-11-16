package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.payment.api.GatewayResult;
import com.wooyongjeong.limitedsale.payment.api.PaymentDetails;
import com.wooyongjeong.limitedsale.payment.api.PaymentGateway;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderPaymentServiceTest {
    @Test
    void aMissingPaymentIsClosedIfTheOrderExpiredDuringTheQuery() {
        var idToken = UUID.randomUUID();
        var transaction = mock(OrderPaymentTransaction.class);
        var gateway = mock(PaymentGateway.class);
        var id = UUID.randomUUID();
        var payment = new PaymentDetails(UUID.randomUUID(), 1000, "PROCESSING");
        when(transaction.begin(1, id)).thenReturn(new PaymentAttempt(7, payment, PaymentAttempt.Action.QUERY, idToken));
        when(gateway.query(any(), any())).thenReturn(GatewayResult.MISSING);
        when(transaction.afterMissing(7, idToken)).thenReturn(PaymentAttempt.Action.CLOSE);
        when(gateway.close(any(), any())).thenReturn(GatewayResult.CLOSED);
        new OrderPaymentService(transaction, gateway, Duration.ofSeconds(1)).pay(1, id);
        verify(gateway, never()).charge(any(), any());
        verify(transaction).record(7, idToken, GatewayResult.CLOSED);
    }

    @Test
    void anExhaustedBudgetPreventsASecondCall() {
        var idToken = UUID.randomUUID();
        var transaction = mock(OrderPaymentTransaction.class);
        var gateway = mock(PaymentGateway.class);
        var id = UUID.randomUUID();
        var payment = new PaymentDetails(UUID.randomUUID(), 1000, "PROCESSING");
        when(transaction.begin(1, id)).thenReturn(new PaymentAttempt(7, payment, PaymentAttempt.Action.QUERY, idToken));
        when(gateway.query(any(), any())).thenAnswer(call -> {
            Thread.sleep(100);
            return GatewayResult.MISSING;
        });
        new OrderPaymentService(transaction, gateway, Duration.ofMillis(20)).pay(1, id);
        verify(gateway, never()).charge(any(), any());
        verify(gateway, never()).close(any(), any());
        verify(transaction).record(7, idToken, GatewayResult.UNKNOWN);
    }

    @Test
    void queryAndChargeShareTheCommunicationBudget() {
        var idToken = UUID.randomUUID();
        var transaction = mock(OrderPaymentTransaction.class);
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
        var service = new OrderPaymentService(transaction, gateway, Duration.ofSeconds(1));
        service.pay(1, orderId);
        var remaining = ArgumentCaptor.forClass(Duration.class);
        verify(gateway).charge(any(), remaining.capture());
        assertThat(remaining.getValue()).isPositive().isLessThan(Duration.ofMillis(950));
        verify(transaction).record(7, idToken, GatewayResult.SUCCEEDED);
    }    @Test
    void databaseDelayConsumesTheSharedCommunicationBudget() {
        var token = UUID.randomUUID();
        var transaction = mock(OrderPaymentTransaction.class);
        var gateway = mock(PaymentGateway.class);
        var id = UUID.randomUUID();
        var payment = new PaymentDetails(UUID.randomUUID(), 1000, "PROCESSING");
        when(transaction.begin(1, id)).thenReturn(new PaymentAttempt(7, payment, PaymentAttempt.Action.QUERY, token));
        when(gateway.query(any(), any())).thenReturn(GatewayResult.MISSING);
        when(transaction.afterMissing(7, token)).thenAnswer(call -> {
            Thread.sleep(100);
            return PaymentAttempt.Action.CHARGE;
        });
        new OrderPaymentService(transaction, gateway, Duration.ofMillis(20)).pay(1, id);
        verify(gateway, never()).charge(any(), any());
        verify(gateway, never()).close(any(), any());
    }

}
