package com.wooyongjeong.limitedsale.payment.api;

import java.time.Instant;

public interface PaymentPreparation {
    void prepare(long orderId, long amount, Instant time);
}
