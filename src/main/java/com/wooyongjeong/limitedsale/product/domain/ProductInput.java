package com.wooyongjeong.limitedsale.product.domain;

public record ProductInput(String name, String description, long basePrice) {
    public static ProductInput create(String name, String description, Long basePrice) {
        String normalized = name == null ? "" : name.strip();
        String text = description == null ? "" : description;
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > 100) {
            throw new ProductValidationException("name", "제품명은 공백 제거 후 1~100자여야 합니다");
        }
        if (text.codePointCount(0, text.length()) > 2000) {
            throw new ProductValidationException("description", "설명은 2000자 이하여야 합니다");
        }
        if (basePrice == null || basePrice < 1 || basePrice > 1_000_000_000L) {
            throw new ProductValidationException("basePrice", "기준 가격은 1~1000000000원이어야 합니다");
        }
        return new ProductInput(normalized, text, basePrice);
    }
}
