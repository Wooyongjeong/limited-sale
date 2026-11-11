package com.wooyongjeong.limitedsale.sale.domain;

import java.time.Instant;

public record SaleTerms(long productId, String name, String description, long unitPrice,
        Instant startsAt, Instant endsAt, int totalQuantity, int perMemberLimit) {
    private static final Instant MIN_TIME = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999999Z");

    public SaleTerms {
        if (productId <= 0) {
            throw SaleException.invalid("productId", "제품 ID는 양의 정수여야 합니다");
        }
        name = name == null ? "" : name.strip();
        description = description == null ? "" : description;
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 100) {
            throw SaleException.invalid("name", "이벤트명은 공백 제거 후 1~100자여야 합니다");
        }
        if (description.codePointCount(0, description.length()) > 2000) {
            throw SaleException.invalid("description", "설명은 2000자 이하여야 합니다");
        }
        if (unitPrice < 1 || unitPrice > 1_000_000_000L) {
            throw SaleException.invalid("unitPrice", "판매 가격은 1~1000000000원이어야 합니다");
        }
        validateTime(startsAt, "startsAt");
        validateTime(endsAt, "endsAt");
        if (!startsAt.isBefore(endsAt)) {
            throw SaleException.invalid("endsAt", "종료 시각은 시작 시각보다 뒤여야 합니다");
        }
        if (totalQuantity < 1 || totalQuantity > 1_000_000) {
            throw SaleException.invalid("totalQuantity", "총수량은 1~1000000개여야 합니다");
        }
        if (perMemberLimit < 1 || perMemberLimit > totalQuantity) {
            throw SaleException.invalid("perMemberLimit", "구매 한도는 1~총수량이어야 합니다");
        }
    }

    private static void validateTime(Instant time, String field) {
        if (time == null || time.isBefore(MIN_TIME) || time.isAfter(MAX_TIME) || time.getNano() % 1000 != 0) {
            throw SaleException.invalid(field, "시각은 UTC 1000~9999년의 마이크로초 정밀도여야 합니다");
        }
    }

    public void requireFutureStart(Instant now) {
        if (!startsAt.isAfter(now)) {
            throw SaleException.invalid("startsAt", "시작 시각은 현재 DB 시각보다 미래여야 합니다");
        }
    }
}
