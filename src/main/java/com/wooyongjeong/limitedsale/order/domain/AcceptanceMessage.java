package com.wooyongjeong.limitedsale.order.domain;

import java.util.UUID;

/**
 * The outbox row stored with every accepted request. The key is the sale ID so one sale always lands in one partition;
 * the content names the acceptance only, because the consumer reads the stored acceptance, not the message.
 */
public record AcceptanceMessage(String topic, String key, String payload) {
    public static final int SCHEMA_VERSION = 1;

    public static AcceptanceMessage of(String topic, UUID acceptanceId, long saleId) {
        // Only a UUID and a number go in, so nothing needs JSON escaping.
        return new AcceptanceMessage(topic, Long.toString(saleId), "{\"schemaVersion\":" + SCHEMA_VERSION
            + ",\"acceptanceId\":\"" + acceptanceId + "\",\"saleId\":" + saleId + "}");
    }
}
