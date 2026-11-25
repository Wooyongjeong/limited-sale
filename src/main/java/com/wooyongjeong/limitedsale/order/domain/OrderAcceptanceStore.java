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

    /** Locks the acceptance row until the caller's transaction ends; empty when there is no such acceptance. */
    Optional<AcceptanceInProcess> lockForProcessing(UUID acceptanceId);

    /** Moves a locked ACCEPTED acceptance to ORDER_CREATED with the internal order ID. */
    void finishWithOrder(UUID acceptanceId, long orderId, Instant now);

    /** Moves a locked ACCEPTED acceptance to REJECTED. */
    void finishRejected(UUID acceptanceId, OrderRejection rejection, Instant now);

    /** Moves a locked ACCEPTED acceptance to PROCESSING_FAILED. */
    void finishFailed(UUID acceptanceId, ProcessingFailure reason, Instant now);
}
