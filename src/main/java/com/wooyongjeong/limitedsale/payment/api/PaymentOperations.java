package com.wooyongjeong.limitedsale.payment.api;

import java.time.Instant;

public interface PaymentOperations {
    PaymentDetails find(long orderId);
    PaymentDetails lock(long orderId);
    PaymentDetails markStarted(long orderId, Instant now);
    PaymentDetails record(long orderId, String status, Instant now);
}
