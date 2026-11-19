package com.wooyongjeong.limitedsale.payment.api;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

public interface PaymentOperations {
    PaymentDetails find(long orderId);
    Map<Long, String> statuses(Collection<Long> orderIds);
    PaymentDetails lock(long orderId);
    PaymentDetails markStarted(long orderId, Instant now);
    PaymentDetails record(long orderId, String status, Instant now);
}
