package com.wooyongjeong.mockpg.presentation.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

@JsonDeserialize(using = ChargeRequest.Deserializer.class)
public record ChargeRequest(JsonNode value) {
    public static class Deserializer extends JsonDeserializer<ChargeRequest> {
        @Override
        public ChargeRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return new ChargeRequest(RequestJson.read(parser));
        }

        @Override
        public ChargeRequest getNullValue(DeserializationContext context) throws JsonMappingException {
            return context.reportInputMismatch(ChargeRequest.class, "Expected one JSON object");
        }
    }
}
