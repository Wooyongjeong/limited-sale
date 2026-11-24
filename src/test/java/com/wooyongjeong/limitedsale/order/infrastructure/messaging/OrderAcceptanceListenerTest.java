package com.wooyongjeong.limitedsale.order.infrastructure.messaging;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderAcceptanceListenerTest {
    private static final UUID ID = UUID.fromString("0b6f0f35-3a3c-4a43-9a43-3b1c2f9c4e11");

    @Test
    void readsTheMessageOfThe33Contract() {
        assertThat(OrderAcceptanceListener.read("{\"schemaVersion\":1,\"acceptanceId\":\"" + ID + "\",\"saleId\":7}"))
            .isEqualTo(new OrderAcceptanceListener.Message(ID, 7));
    }

    @Test
    void anythingElseIsUnreadable() {
        assertThat(OrderAcceptanceListener.read(null)).isNull();
        assertThat(OrderAcceptanceListener.read("not json")).isNull();
        assertThat(OrderAcceptanceListener.read("[]")).isNull();
        assertThat(OrderAcceptanceListener.read("{\"schemaVersion\":2,\"acceptanceId\":\"" + ID + "\",\"saleId\":7}"))
            .isNull();
        assertThat(OrderAcceptanceListener.read("{\"schemaVersion\":\"1\",\"acceptanceId\":\"" + ID
            + "\",\"saleId\":7}")).isNull();
        assertThat(OrderAcceptanceListener.read("{\"schemaVersion\":1,\"acceptanceId\":\"nope\",\"saleId\":7}"))
            .isNull();
        assertThat(OrderAcceptanceListener.read("{\"schemaVersion\":1,\"acceptanceId\":\"" + ID + "\",\"saleId\":\"7\"}"))
            .isNull();
        assertThat(OrderAcceptanceListener.read("{\"schemaVersion\":1,\"acceptanceId\":\"" + ID + "\"}")).isNull();
    }
}
