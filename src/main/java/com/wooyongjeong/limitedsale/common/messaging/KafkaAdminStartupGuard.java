package com.wooyongjeong.limitedsale.common.messaging;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Keeps Spring Kafka's admin from creating topics while the context starts, whatever the property sources say.
 * That step blocks for the admin client's default 60 s when no broker answers and leaves a client retrying in the
 * background. A context without this application's application.yml (as some integration tests have, because
 * another application.yml shadows it, as the Mock PG's did in integration tests until stage 4-2) would otherwise fall back
 * to the Boot default and do it.
 * {@link KafkaTopicProvisioner} creates the topics instead.
 */
@Configuration(proxyBeanMethods = false)
class KafkaAdminStartupGuard {
    @Bean
    static BeanPostProcessor kafkaAdminWithoutStartupCreation() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof KafkaAdmin admin) {
                    admin.setAutoCreate(false);
                }
                return bean;
            }
        };
    }
}
