package com.wooyongjeong.limitedsale.order.presentation;

import tools.jackson.databind.JsonNode;
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
        body.propertyNames().forEach(field -> {
            if (!Set.of("saleId", "quantity").contains(field)) {
                throw new InvalidOrderInput("body", "알 수 없는 요청 필드가 있습니다");
            }
        });
    }

    static int pageNumber(String input, String field) {
        long value;
        try {
            if (!input.matches("[0-9]+")) {
                throw new NumberFormatException();
            }
            value = Long.parseLong(input);
        } catch (NumberFormatException exception) {
            throw new InvalidOrderInput(field, "0 이상의 정수를 입력하세요");
        }
        if (value > Integer.MAX_VALUE) {
            throw new InvalidOrderInput(field, "정수 범위를 초과했습니다");
        }
        return (int) value;
    }

    static int pageSize(String input) {
        int value = pageNumber(input, "size");
        if (value < 1 || value > 100) {
            throw new InvalidOrderInput("size", "페이지 크기는 1~100이어야 합니다");
        }
        return value;
    }

    static UUID uuid(String input, String field) {
        if (input == null || !input.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new InvalidOrderInput(field, "UUID 형식을 확인하세요");
        }
        return UUID.fromString(input);
    }
}
