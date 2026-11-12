package com.wooyongjeong.limitedsale.order.presentation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;

final class OrderJson {
    private OrderJson() {
    }

    @JsonDeserialize(using = Deserializer.class)
    record Body(JsonNode value) {
    }

    static class Deserializer extends JsonDeserializer<Body> {
        @Override
        public Body deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode value = parser.getCodec().readTree(parser);
            if (value == null || !value.isObject() || parser.nextToken() != null) {
                throw JsonMappingException.from(parser, "Expected one JSON object");
            }
            return new Body(value);
        }

        @Override
        public Body getNullValue(DeserializationContext context) throws JsonMappingException {
            return context.reportInputMismatch(Body.class, "Expected one JSON object");
        }
    }

    static long number(JsonNode body, String field, long max) {
        JsonNode value = body.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 1 || value.longValue() > max) {
            throw new InvalidOrderInput(field, "양의 정수 범위를 확인하세요");
        }
        return value.longValue();
    }

    static void fields(JsonNode body) {
        body.fieldNames().forEachRemaining(field -> {
            if (!Set.of("saleId", "quantity").contains(field)) {
                throw new InvalidOrderInput("body", "알 수 없는 요청 필드가 있습니다");
            }
        });
    }

    static UUID uuid(String input, String field) {
        if (input == null || !input.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new InvalidOrderInput(field, "UUID 형식을 확인하세요");
        }
        return UUID.fromString(input);
    }
}
