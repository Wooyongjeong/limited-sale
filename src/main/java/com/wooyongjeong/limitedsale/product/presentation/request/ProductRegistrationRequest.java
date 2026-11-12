package com.wooyongjeong.limitedsale.product.presentation.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

@JsonDeserialize(using = ProductRegistrationRequest.Deserializer.class)
public record ProductRegistrationRequest(JsonNode input) {
    public static class Deserializer extends JsonDeserializer<ProductRegistrationRequest> {
        @Override
        public ProductRegistrationRequest deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode input = parser.getCodec().readTree(parser);
            if (parser.nextToken() != null) {
                throw JsonMappingException.from(parser, "Expected one JSON value");
            }
            return new ProductRegistrationRequest(input);
        }
    }
}
