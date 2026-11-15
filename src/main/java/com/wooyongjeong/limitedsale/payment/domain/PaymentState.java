package com.wooyongjeong.limitedsale.payment.domain;

public enum PaymentState {
    READY, PROCESSING, UNKNOWN, SUCCEEDED, FAILED, CLOSED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CLOSED;
    }

    public PaymentState apply(PaymentState incoming) {
        return terminal() ? this : incoming;
    }
}
