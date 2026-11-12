package com.wooyongjeong.limitedsale.order.domain;

public final class OrderRequestKeyCollision extends RuntimeException {
    public OrderRequestKeyCollision(Throwable cause) {
        super("Order request key already exists", cause);
    }
}
