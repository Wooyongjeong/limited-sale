package com.wooyongjeong.limitedsale.order.domain;

/** The topic that carries accepted order requests, named here so no layer needs a Kafka class to know it. */
public final class OrderAcceptanceTopic {
    public static final String NAME = "limited-sale.order-acceptances.v1";
    public static final int PARTITIONS = 6;
    public static final short REPLICAS = 1;

    private OrderAcceptanceTopic() {
    }
}
