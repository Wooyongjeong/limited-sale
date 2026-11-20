package com.wooyongjeong.mockpg.presentation.request;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = SimulationRequest.Deserializer.class)
public record SimulationRequest(JsonNode value) {
    public static class Deserializer extends ValueDeserializer<SimulationRequest> {
        @Override
        public SimulationRequest deserialize(JsonParser parser, DeserializationContext context) {
            return new SimulationRequest(RequestJson.read(parser));
        }

        @Override
        public SimulationRequest getNullValue(DeserializationContext context) {
            return context.reportInputMismatch(SimulationRequest.class, "Expected one JSON object");
        }
    }
}
