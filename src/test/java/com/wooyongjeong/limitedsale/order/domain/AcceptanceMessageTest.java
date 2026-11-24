package com.wooyongjeong.limitedsale.order.domain;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AcceptanceMessageTest {
    @Test
    void payloadNamesTheAcceptanceAndSaleAndTheKeyIsTheSaleId() {
        UUID id = UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");

        var message = AcceptanceMessage.of("limited-sale.order-acceptances.v1", id, 9_007_199_254_740_993L);

        assertThat(message.topic()).isEqualTo("limited-sale.order-acceptances.v1");
        assertThat(message.key()).isEqualTo("9007199254740993");
        assertThat(message.payload()).isEqualTo(
            "{\"schemaVersion\":1,\"acceptanceId\":\"0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d\","
                + "\"saleId\":9007199254740993}");
    }
}
