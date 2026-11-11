package com.wooyongjeong.limitedsale.product.domain;

import java.util.List;

public class ProductValidationException extends RuntimeException {
    private final List<FieldError> errors;

    public ProductValidationException(String field, String reason) {
        this.errors = List.of(new FieldError(field, reason));
    }

    public List<FieldError> errors() {
        return errors;
    }

    public record FieldError(String field, String reason) {
    }
}
