package com.wooyongjeong.limitedsale.common.session;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.SessionRepository;

/** Wraps the application's session repository in {@link AccessTimeTolerantSessionRepository}. */
@Configuration(proxyBeanMethods = false)
class SessionRepositoryTolerance {
    @Bean
    static BeanPostProcessor accessTimeTolerantSessionRepository(ObjectProvider<MeterRegistry> registry) {
        return new BeanPostProcessor() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof SessionRepository<?> repository)
                        || bean instanceof AccessTimeTolerantSessionRepository<?>) {
                    return bean;
                }
                Counter skipped = Counter.builder("session.save.skipped")
                    .description("End-of-request session saves that failed in Redis and were skipped; the response "
                        + "stood because its work was already done").register(registry.getObject());
                return new AccessTimeTolerantSessionRepository(repository, skipped);
            }
        };
    }
}
