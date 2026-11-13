package com.wooyongjeong.mockpg.presentation.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

@JsonDeserialize(using = CloseRequest.Deserializer.class)
public record CloseRequest(JsonNode value) {
    public static class Deserializer extends JsonDeserializer<CloseRequest> {
        @Override
        public CloseRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return new CloseRequest(RequestJson.read(parser));
        }

        @Override
        public CloseRequest getNullValue(DeserializationContext context) throws JsonMappingException {
            return context.reportInputMismatch(CloseRequest.class, "Expected one JSON object");
        }
    }
}
