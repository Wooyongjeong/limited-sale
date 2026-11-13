package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.api.OrderLookup;
import com.wooyongjeong.limitedsale.payment.api.PaymentSimulation;
import com.wooyongjeong.limitedsale.payment.api.SimulationConfiguration;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentSimulationServiceTest {
    @Test
    void missingOrderNeverCallsPayment() {
        OrderLookup orders = mock(OrderLookup.class);
        PaymentSimulation payment = mock(PaymentSimulation.class);
        UUID id = UUID.randomUUID();
        when(orders.findId(id)).thenReturn(OptionalLong.empty());
        var service = new PaymentSimulationService(orders, payment);
        var config = new SimulationConfiguration("SUCCESS", null);
        assertThat(service.configure(id, config)).isFalse();
        verify(payment, never()).configure(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }
}
