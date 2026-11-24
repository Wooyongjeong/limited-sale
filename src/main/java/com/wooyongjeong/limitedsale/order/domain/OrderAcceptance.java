package com.wooyongjeong.limitedsale.order.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * What an accepted, rejected or finished request looks like to its owner. {@code orderId} is the public order ID and
 * is set only for ORDER_CREATED; {@code rejection} only for REJECTED; {@code failureReason} only for PROCESSING_FAILED.
 */
public record OrderAcceptance(UUID acceptanceId, long saleId, int quantity, AcceptanceStatus status,
        OrderRejection rejection, String failureReason, UUID orderId, Instant acceptedAt, Instant finishedAt) {
}
