package com.wooyongjeong.limitedsale.order.domain;

import java.time.Instant;
import java.util.UUID;

public record PaymentOrder(long id, UUID publicId, long memberId, long saleId, int quantity, String status,
        Instant expiresAt, Instant nextRecoveryAt, UUID leaseToken, Instant leaseUntil, boolean requiresReview,
        Instant nextReviewWarningAt) {
}
