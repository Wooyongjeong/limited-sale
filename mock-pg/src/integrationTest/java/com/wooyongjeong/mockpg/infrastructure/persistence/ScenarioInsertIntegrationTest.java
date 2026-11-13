package com.wooyongjeong.mockpg.infrastructure.persistence;

import com.wooyongjeong.mockpg.domain.Scenario;
import com.wooyongjeong.mockpg.domain.ScenarioConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ScenarioInsertIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.7");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl()
                + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("mock-pg.api-key", () -> "integration-test-key");
    }

    @Autowired
    ScenarioJpaRepository scenarios;
    @Autowired
    PaymentOperations operations;

    @Test
    void staleDefaultInsertCannotOverwriteCommittedScenario() {
        UUID key = UUID.randomUUID();
        // Another request commits after the first request found no row and prepared this default insert.
        var pendingDefault = new ScenarioEntity(key);
        var configured = new ScenarioConfiguration(Scenario.DELAY, 600);
        operations.configure(key, configured);

        Throwable failure = catchThrowable(() -> scenarios.saveAndFlush(pendingDefault));
        assertThat(scenarios.findById(key).orElseThrow().configuration()).isEqualTo(configured);
        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
    }
}
