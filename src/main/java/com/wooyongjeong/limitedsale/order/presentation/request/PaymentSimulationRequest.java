package com.wooyongjeong.limitedsale.order.presentation.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

@JsonDeserialize(using = PaymentSimulationRequest.Deserializer.class)
public record PaymentSimulationRequest(JsonNode value) {
    public static class Deserializer extends JsonDeserializer<PaymentSimulationRequest> {
        @Override
        public PaymentSimulationRequest deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode value = parser.getCodec().readTree(parser);
            if (value == null || !value.isObject() || parser.nextToken() != null) {
                throw JsonMappingException.from(parser, "Expected one JSON object");
            }
            return new PaymentSimulationRequest(value);
        }

        @Override
        public PaymentSimulationRequest getNullValue(DeserializationContext context) throws JsonMappingException {
            return context.reportInputMismatch(PaymentSimulationRequest.class, "Expected one JSON object");
        }
    }
}
