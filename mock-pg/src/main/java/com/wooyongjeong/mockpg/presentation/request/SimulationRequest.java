package com.wooyongjeong.mockpg.presentation.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

@JsonDeserialize(using = SimulationRequest.Deserializer.class)
public record SimulationRequest(JsonNode value) {
    public static class Deserializer extends JsonDeserializer<SimulationRequest> {
        @Override
        public SimulationRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return new SimulationRequest(RequestJson.read(parser));
        }

        @Override
        public SimulationRequest getNullValue(DeserializationContext context) throws JsonMappingException {
            return context.reportInputMismatch(SimulationRequest.class, "Expected one JSON object");
        }
    }
}
