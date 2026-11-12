package com.wooyongjeong.limitedsale.payment.infrastructure.persistence;

public enum PaymentStatus {
    READY,
    PROCESSING,
    UNKNOWN,
    SUCCEEDED,
    FAILED,
    CLOSED
}
