package com.wooyongjeong.limitedsale.order.domain;

import java.time.Instant;

/**
 * A stored acceptance as the consumer sees it, read under its row lock. {@code requestId} is the HTTP request id of the
 * request that created it (null for acceptances stored before it was recorded).
 */
public record AcceptanceInProcess(long memberId, long saleId, int quantity, AcceptanceStatus status,
        Instant acceptedAt, String requestId) {
    public boolean finished() {
        return status != AcceptanceStatus.ACCEPTED;
    }
}
