package com.wooyongjeong.limitedsale.sale.presentation.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

@JsonDeserialize(using = SaleRequest.Deserializer.class)
public record SaleRequest(JsonNode value) {
    public static class Deserializer extends JsonDeserializer<SaleRequest> {
        @Override
        public SaleRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode value = parser.getCodec().readTree(parser);
            if (value == null || !value.isObject() || parser.nextToken() != null) {
                throw JsonMappingException.from(parser, "Expected one JSON object");
            }
            return new SaleRequest(value);
        }

        @Override
        public SaleRequest getNullValue(DeserializationContext context) throws JsonMappingException {
            return context.reportInputMismatch(SaleRequest.class, "Expected one JSON object");
        }
    }
}
