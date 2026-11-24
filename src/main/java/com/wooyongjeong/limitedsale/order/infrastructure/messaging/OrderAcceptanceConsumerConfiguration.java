package com.wooyongjeong.limitedsale.order.infrastructure.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Until 3-6 adds a bounded retry and a stored failure, a message whose processing fails (the database is away, a lock
 * wait timed out) is tried again every second for as long as it takes. Its partition waits meanwhile; nothing is lost
 * or skipped. Spring Boot hands this error handler to the listener container factory.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"limited-sale.order.async.enabled", "limited-sale.order.consumer.enabled"},
    havingValue = "true")
class OrderAcceptanceConsumerConfiguration {
    @Bean
    DefaultErrorHandler orderAcceptanceErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
