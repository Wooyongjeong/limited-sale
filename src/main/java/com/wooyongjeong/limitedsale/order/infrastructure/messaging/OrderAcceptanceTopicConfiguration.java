package com.wooyongjeong.limitedsale.order.infrastructure.messaging;

import com.wooyongjeong.limitedsale.order.domain.OrderAcceptanceTopic;
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
    @Bean
    NewTopic orderAcceptancesTopic() {
        return TopicBuilder.name(OrderAcceptanceTopic.NAME)
                .partitions(OrderAcceptanceTopic.PARTITIONS)
                .replicas(OrderAcceptanceTopic.REPLICAS)
                .build();
    }
}
