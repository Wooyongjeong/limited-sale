package com.wooyongjeong.limitedsale.order.infrastructure.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The operational topic of the asynchronous order path. Six partitions let two instances with three consumers each
 * share it; replication stays 1 because the local broker is a single node (no high availability).
 */
@Configuration(proxyBeanMethods = false)
public class OrderAcceptanceTopicConfiguration {
    public static final String ORDER_ACCEPTANCES_TOPIC = "limited-sale.order-acceptances.v1";
    public static final int ORDER_ACCEPTANCES_PARTITIONS = 6;
    public static final short ORDER_ACCEPTANCES_REPLICAS = 1;

    @Bean
    NewTopic orderAcceptancesTopic() {
        return TopicBuilder.name(ORDER_ACCEPTANCES_TOPIC)
                .partitions(ORDER_ACCEPTANCES_PARTITIONS)
                .replicas(ORDER_ACCEPTANCES_REPLICAS)
                .build();
    }
}
