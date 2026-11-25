package com.wooyongjeong.limitedsale.common;

import com.wooyongjeong.limitedsale.LimitedSaleApplication;
import com.wooyongjeong.limitedsale.member.application.MemberService;
import com.wooyongjeong.limitedsale.product.api.ProductCommand;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.PriorityOrdered;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 2-5 finding 1 (fixed in 4-2): when Redis fails at the session save that runs as the response is committed,
 * an order that is already committed must still reach the client as an intact 201. The failure is injected into the
 * real Redis session repository's save only, so everything before it (session read, in-request writes) is real.
 */
class SessionSaveFailureIntegrationTest {
    private static final MySQLContainer DB = new MySQLContainer("mysql:8.4.7")
        .withCommand("--log-bin-trust-function-creators=1");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final AtomicBoolean SAVE_FAILS = new AtomicBoolean();
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;
    private static int port;

    @BeforeAll
    static void start() {
        DB.start();
        REDIS.start();
        app = new SpringApplicationBuilder(LimitedSaleApplication.class, FailingSessionSave.class).run(
            "--server.port=0", "--management.server.port=0", "--limited-sale.order.rate-limit.enabled=false",
            "--spring.config.additional-location=file:src/main/resources/application.yml",
            "--spring.profiles.active=test", "--limited-sale.recovery.enabled=false", "--MOCK_PG_API_KEY=test-secret",
            "--spring.flyway.enabled=true", "--spring.jpa.hibernate.ddl-auto=validate",
            "--spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration",
            "--spring.datasource.url=" + DB.getJdbcUrl(), "--spring.datasource.username=" + DB.getUsername(),
            "--spring.datasource.password=" + DB.getPassword(), "--spring.data.redis.host=" + REDIS.getHost(),
            "--spring.data.redis.port=" + REDIS.getMappedPort(6379));
        jdbc = app.getBean(JdbcTemplate.class);
        port = ((ServletWebServerApplicationContext) app).getWebServer().getPort();
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
        REDIS.stop();
        DB.stop();
    }

    /** Makes the real repository's save throw what a Redis timeout throws while SAVE_FAILS is set. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FailingSessionSave {
        @Bean
        // Declared as FailingSave so the container sees PriorityOrdered and applies it before the application's wrapper.
        static FailingSave failingSessionSave() {
            return new FailingSave();
        }
    }

    /** Runs before the application's own wrapper (PriorityOrdered), so the failure comes from inside it. */
    static final class FailingSave implements BeanPostProcessor, PriorityOrdered {
        @Override
        public int getOrder() {
            return 0;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!(bean instanceof SessionRepository<?> repository)) {
                return bean;
            }
            SessionRepository real = repository;
            return new SessionRepository<Session>() {
                @Override
                public Session createSession() {
                    return real.createSession();
                }

                @Override
                public void save(Session session) {
                    if (SAVE_FAILS.get()) {
                        throw new QueryTimeoutException("injected: Redis command timed out");
                    }
                    real.save(session);
                }

                @Override
                public Session findById(String id) {
                    return (Session) real.findById(id);
                }

                @Override
                public void deleteById(String id) {
                    real.deleteById(id);
                }
            };
        }
    }

    private static HttpResponse<String> send(HttpClient http, String method, String path, String body, String csrf,
            String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(15));
        if (csrf != null) {
            builder.header("X-CSRF-TOKEN", csrf);
        }
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String csrf(HttpClient http) throws Exception {
        return JSON.readTree(send(http, "GET", "/api/v1/csrf", null, null, null).body()).path("data").path("token")
            .asString();
    }

    @Test
    void aCommittedOrderStillGetsItsWhole201WhenTheEndOfRequestSessionSaveFails() throws Exception {
        app.getBean(MemberService.class).register("session-save@example.com", "password", "sessionsave");
        long product = app.getBean(ProductCommand.class).register("product", "", 1000L).productId();
        jdbc.update("INSERT INTO sales(product_id,name,description,product_name_snapshot,unit_price,starts_at,ends_at,"
            + "total_quantity,per_member_limit,created_at,updated_at) VALUES (?, 'sale', '', 'product', 1000, "
            + "UTC_TIMESTAMP(6)-INTERVAL 1 HOUR, UTC_TIMESTAMP(6)+INTERVAL 1 HOUR, 5, 2, UTC_TIMESTAMP(6), "
            + "UTC_TIMESTAMP(6))", product);
        long sale = jdbc.queryForObject("SELECT MAX(id) FROM sales", Long.class);
        HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        assertThat(send(http, "POST", "/api/v1/sessions",
            "{\"email\":\"session-save@example.com\",\"password\":\"password\"}", csrf(http), null).statusCode())
            .isEqualTo(200);
        String token = csrf(http);
        var skipped = app.getBean(MeterRegistry.class).find("session.save.skipped").counter();
        double before = skipped.count();

        SAVE_FAILS.set(true);
        HttpResponse<String> created;
        try {
            created = send(http, "POST", "/api/v1/orders", "{\"saleId\":" + sale + ",\"quantity\":1}", token,
                UUID.randomUUID().toString());
        } finally {
            SAVE_FAILS.set(false);
        }

        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode body = JSON.readTree(created.body());
        assertThat(body.path("code").asString()).isEqualTo("ORDER_CREATED");
        String orderId = body.path("data").path("orderId").asString();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE public_id = "
            + "UNHEX(REPLACE(?, '-', ''))", Integer.class, orderId)).isEqualTo(1);
        assertThat(skipped.count()).isGreaterThan(before);
        // The session itself is fine: the next request with the same cookie is still signed in.
        assertThat(send(http, "GET", "/api/v1/orders/" + orderId, null, null, null).statusCode()).isEqualTo(200);
    }
}
