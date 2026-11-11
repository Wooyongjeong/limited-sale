package com.wooyongjeong.limitedsale.sale.domain;

public class SaleException extends RuntimeException {
    public enum Kind { INVALID, NOT_FOUND, PRODUCT_NOT_FOUND, STARTED, ENDED, VERSION_CONFLICT }

    private final Kind kind;
    private final String field;

    public SaleException(Kind kind, String message) {
        this(kind, null, message);
    }

    private SaleException(Kind kind, String field, String message) {
        super(message);
        this.kind = kind;
        this.field = field;
    }

    public static SaleException invalid(String field, String reason) {
        return new SaleException(Kind.INVALID, field, reason);
    }

    public Kind kind() {
        return kind;
    }

    public String field() {
        return field;
    }
}
