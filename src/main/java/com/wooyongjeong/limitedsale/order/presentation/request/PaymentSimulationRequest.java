package com.wooyongjeong.limitedsale.order.presentation.request;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = PaymentSimulationRequest.Deserializer.class)
public record PaymentSimulationRequest(JsonNode value) {
    public static class Deserializer extends ValueDeserializer<PaymentSimulationRequest> {
        @Override
        public PaymentSimulationRequest deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode value = parser.readValueAsTree();
            if (value == null || !value.isObject() || parser.nextToken() != null) {
                throw DatabindException.from(parser, "Expected one JSON object");
            }
            return new PaymentSimulationRequest(value);
        }

        @Override
        public PaymentSimulationRequest getNullValue(DeserializationContext context) {
            return context.reportInputMismatch(PaymentSimulationRequest.class, "Expected one JSON object");
        }
    }
}
