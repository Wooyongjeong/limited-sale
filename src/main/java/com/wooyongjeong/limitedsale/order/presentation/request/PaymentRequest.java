package com.wooyongjeong.limitedsale.order.presentation.request;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = PaymentRequest.Deserializer.class)
public record PaymentRequest() {
    public static class Deserializer extends ValueDeserializer<PaymentRequest> {
        @Override
        public PaymentRequest deserialize(JsonParser parser, DeserializationContext context) {
            throw DatabindException.from(parser, "Payment request must not have a body");
        }

        @Override
        public PaymentRequest getNullValue(DeserializationContext context) {
            return context.reportInputMismatch(PaymentRequest.class, "Payment request must not have a body");
        }
    }
}
