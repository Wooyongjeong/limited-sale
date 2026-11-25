package com.wooyongjeong.mockpg;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Makes the Mock PG read its own settings from mock-pg.yml instead of application.yml (stage 4-2). The sales
 * application's integration tests put this module on their classpath, where an application.yml of the Mock PG used to
 * shadow the sales one, so a sales context without an explicit config location silently ran with the Mock PG's
 * settings. Only an application whose sources include {@link MockPgApplication} is affected, and an explicit
 * spring.config.name or spring.config.location still wins (this is the lowest-precedence source).
 */
public class MockPgConfigName implements EnvironmentPostProcessor, Ordered {
    @Override
    public int getOrder() {
        // Before ConfigDataEnvironmentPostProcessor reads spring.config.name.
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (application.getAllSources().contains(MockPgApplication.class)) {
            environment.getPropertySources().addLast(
                new MapPropertySource("mockPgConfigName", Map.of("spring.config.name", "mock-pg")));
        }
    }
}
