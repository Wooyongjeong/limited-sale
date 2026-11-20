package com.wooyongjeong.limitedsale.product.presentation.request;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = ProductRegistrationRequest.Deserializer.class)
public record ProductRegistrationRequest(JsonNode input) {
    public static class Deserializer extends ValueDeserializer<ProductRegistrationRequest> {
        @Override
        public ProductRegistrationRequest deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode input = parser.readValueAsTree();
            if (parser.nextToken() != null) {
                throw DatabindException.from(parser, "Expected one JSON value");
            }
            return new ProductRegistrationRequest(input);
        }
    }
}
