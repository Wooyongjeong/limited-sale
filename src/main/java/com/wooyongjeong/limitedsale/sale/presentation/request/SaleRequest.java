package com.wooyongjeong.limitedsale.sale.presentation.request;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = SaleRequest.Deserializer.class)
public record SaleRequest(JsonNode value) {
    public static class Deserializer extends ValueDeserializer<SaleRequest> {
        @Override
        public SaleRequest deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode value = parser.readValueAsTree();
            if (value == null || !value.isObject() || parser.nextToken() != null) {
                throw DatabindException.from(parser, "Expected one JSON object");
            }
            return new SaleRequest(value);
        }

        @Override
        public SaleRequest getNullValue(DeserializationContext context) {
            return context.reportInputMismatch(SaleRequest.class, "Expected one JSON object");
        }
    }
}
