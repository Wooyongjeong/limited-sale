package com.wooyongjeong.limitedsale.order.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PaymentOrderStore {
    Optional<PaymentOrder> lock(long memberId, UUID publicId);
    PaymentOrder lock(long orderId);
    Instant databaseTime();
    void pending(long orderId, Instant now, Instant retryAt);
    void finish(long orderId, String status, Instant now);
}
