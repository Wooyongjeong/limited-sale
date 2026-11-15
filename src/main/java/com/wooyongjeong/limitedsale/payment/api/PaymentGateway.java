package com.wooyongjeong.limitedsale.payment.api;

import java.time.Duration;

public interface PaymentGateway {
    GatewayResult charge(PaymentDetails payment, Duration timeout);
    GatewayResult query(PaymentDetails payment, Duration timeout);
    GatewayResult close(PaymentDetails payment, Duration timeout);
}
