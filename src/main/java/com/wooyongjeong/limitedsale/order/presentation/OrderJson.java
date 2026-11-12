package com.wooyongjeong.limitedsale.order.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.UUID;

final class OrderJson {
    private OrderJson() {
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
