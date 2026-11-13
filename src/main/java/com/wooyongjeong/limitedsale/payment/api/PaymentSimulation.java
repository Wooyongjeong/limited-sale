package com.wooyongjeong.limitedsale.payment.api;

public interface PaymentSimulation {
    SimulationConfiguration configure(long orderId, SimulationConfiguration configuration);
}
