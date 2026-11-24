package com.wooyongjeong.limitedsale.order.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OrderAcceptanceStore {
    Optional<OrderAcceptance> request(long memberId, UUID key);

    /** Stores the acceptance and its outbox row together; the caller's transaction makes them commit as one. */
    OrderAcceptance accept(UUID acceptanceId, long memberId, UUID key, long saleId, int quantity, Instant now,
        AcceptanceMessage message);

    OrderAcceptance reject(UUID acceptanceId, long memberId, UUID key, long saleId, int quantity,
        OrderRejection rejection, Instant now);

    Optional<OrderAcceptance> find(long memberId, UUID acceptanceId);
}
