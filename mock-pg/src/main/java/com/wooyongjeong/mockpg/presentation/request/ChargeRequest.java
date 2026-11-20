package com.wooyongjeong.mockpg.presentation.request;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = ChargeRequest.Deserializer.class)
public record ChargeRequest(JsonNode value) {
    public static class Deserializer extends ValueDeserializer<ChargeRequest> {
        @Override
        public ChargeRequest deserialize(JsonParser parser, DeserializationContext context) {
            return new ChargeRequest(RequestJson.read(parser));
        }

        @Override
        public ChargeRequest getNullValue(DeserializationContext context) {
            return context.reportInputMismatch(ChargeRequest.class, "Expected one JSON object");
        }
    }
}
