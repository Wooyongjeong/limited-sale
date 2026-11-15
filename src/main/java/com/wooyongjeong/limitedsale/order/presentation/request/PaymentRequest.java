package com.wooyongjeong.limitedsale.order.presentation.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

@JsonDeserialize(using = PaymentRequest.Deserializer.class)
public record PaymentRequest() {
    public static class Deserializer extends JsonDeserializer<PaymentRequest> {
        @Override
        public PaymentRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            throw JsonMappingException.from(parser, "Payment request must not have a body");
        }

        @Override
        public PaymentRequest getNullValue(DeserializationContext context) throws JsonMappingException {
            return context.reportInputMismatch(PaymentRequest.class, "Payment request must not have a body");
        }
    }
}
