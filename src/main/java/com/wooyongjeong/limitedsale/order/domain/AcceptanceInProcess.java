package com.wooyongjeong.limitedsale.order.domain;

import java.time.Instant;

/** A stored acceptance as the consumer sees it, read under its row lock. */
public record AcceptanceInProcess(long memberId, long saleId, int quantity, AcceptanceStatus status,
        Instant acceptedAt) {
    public boolean finished() {
        return status != AcceptanceStatus.ACCEPTED;
    }
}
