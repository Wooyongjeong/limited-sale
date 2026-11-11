package com.wooyongjeong.limitedsale.sale.presentation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.wooyongjeong.limitedsale.sale.domain.SaleException;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;

final class SaleJson {
    private SaleJson() {
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

    static void fields(JsonNode node, Set<String> allowed) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw SaleException.invalid("body", "알 수 없는 요청 필드가 있습니다");
            }
        });
    }

    static String text(JsonNode node, String field, boolean required) {
        JsonNode value = node.get(field);
        if (value == null && !required) {
            return null;
        }
        if (value == null || !value.isTextual()) {
            throw SaleException.invalid(field, "문자열을 입력하세요");
        }
        return value.textValue();
    }

    static String description(JsonNode node) {
        JsonNode value = node.get("description");
        return value != null && value.isNull() ? "" : text(node, "description", false);
    }

    static Long number(JsonNode node, String field, boolean required) {
        JsonNode value = node.get(field);
        if (value == null && !required) {
            return null;
        }
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw SaleException.invalid(field, "정수를 입력하세요");
        }
        return value.longValue();
    }

    static Integer integer(JsonNode node, String field, boolean required) {
        Long value = number(node, field, required);
        if (value == null) {
            return null;
        }
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw SaleException.invalid(field, "정수 범위를 초과했습니다");
        }
        return value.intValue();
    }

    static Instant time(JsonNode node, String field, boolean required) {
        String value = text(node, field, required);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeException exception) {
            throw SaleException.invalid(field, "오프셋을 포함한 ISO 8601 시각을 입력하세요");
        }
    }

    static long id(String value, String field) {
        long parsed = wholeNumber(value, field);
        if (parsed <= 0) {
            throw SaleException.invalid(field, "ID는 양의 정수여야 합니다");
        }
        return parsed;
    }

    static int pageNumber(String value, String field) {
        long parsed = wholeNumber(value, field);
        if (parsed > Integer.MAX_VALUE) {
            throw SaleException.invalid(field, "정수 범위를 초과했습니다");
        }
        return (int) parsed;
    }

    private static long wholeNumber(String value, String field) {
        try {
            if (!value.matches("[0-9]+")) {
                throw new NumberFormatException();
            }
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw SaleException.invalid(field, "0 이상의 정수를 입력하세요");
        }
    }
}
