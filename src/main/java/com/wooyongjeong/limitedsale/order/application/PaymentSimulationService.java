package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.api.OrderLookup;
import com.wooyongjeong.limitedsale.payment.api.PaymentSimulation;
import com.wooyongjeong.limitedsale.payment.api.SimulationConfiguration;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile({"local", "test"})
public class PaymentSimulationService {
    private final OrderLookup orders;
    private final PaymentSimulation payments;

    public PaymentSimulationService(OrderLookup orders, PaymentSimulation payments) {
        this.orders = orders;
        this.payments = payments;
    }

    public boolean configure(UUID orderId, SimulationConfiguration configuration) {
        OptionalLong id = orders.findId(orderId);
        if (id.isEmpty()) {
            return false;
        }
        payments.configure(id.getAsLong(), configuration);
        return true;
    }
}
