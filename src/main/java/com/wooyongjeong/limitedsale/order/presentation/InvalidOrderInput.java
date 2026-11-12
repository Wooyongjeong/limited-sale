package com.wooyongjeong.limitedsale.order.presentation;

final class InvalidOrderInput extends RuntimeException {
    private final String field;

    InvalidOrderInput(String field, String message) {
        super(message);
        this.field = field;
    }

    String field() {
        return field;
    }
}
