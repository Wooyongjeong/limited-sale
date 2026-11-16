package com.wooyongjeong.limitedsale.order.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentOrderStore {
    Optional<PaymentOrder> lock(long memberId, UUID publicId);
    PaymentOrder lock(long orderId);
    Instant databaseTime();
    List<Long> candidates(int limit);
    Optional<PaymentOrder> tryLock(long orderId);
    void claim(long orderId, UUID token, Instant until);
    void reschedule(long orderId, Instant next, boolean review, Instant nextWarning);
    void release(long orderId);
    void pending(long orderId, Instant now, Instant retryAt);
    void finish(long orderId, String status, Instant now);
}
