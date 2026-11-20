package com.wooyongjeong.mockpg.presentation.request;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = CloseRequest.Deserializer.class)
public record CloseRequest(JsonNode value) {
    public static class Deserializer extends ValueDeserializer<CloseRequest> {
        @Override
        public CloseRequest deserialize(JsonParser parser, DeserializationContext context) {
            return new CloseRequest(RequestJson.read(parser));
        }

        @Override
        public CloseRequest getNullValue(DeserializationContext context) {
            return context.reportInputMismatch(CloseRequest.class, "Expected one JSON object");
        }
    }
}
