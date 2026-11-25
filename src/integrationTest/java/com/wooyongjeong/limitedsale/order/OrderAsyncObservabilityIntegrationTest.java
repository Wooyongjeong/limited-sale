package com.wooyongjeong.limitedsale.order;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wooyongjeong.limitedsale.LimitedSaleApplication;
import com.wooyongjeong.limitedsale.member.application.MemberService;
import com.wooyongjeong.limitedsale.order.application.OrderOutboxPublisher;
import com.wooyongjeong.limitedsale.product.api.ProductCommand;
import com.wooyongjeong.limitedsale.support.FixedPortKafka;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The asynchronous order path's observability (3-7) against real MySQL and a real broker: the backlog gauges while the
 * consumer or the publisher is stopped and after it resumes, the completion and publish delay histograms, and the HTTP
 * request id carried into the consumer's log. The backlog gauges refresh every second here instead of every ten.
 */
class OrderAsyncObservabilityIntegrationTest {
    private static final MySQLContainer SALES = new MySQLContainer("mysql:8.4.7")
        .withCommand("--log-bin-trust-function-creators=1");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final KafkaContainer KAFKA = FixedPortKafka.create(FixedPortKafka.freePort());
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger MEMBERS = new AtomicInteger();
    private static final ListAppender<ILoggingEvent> LISTENER_LOG = new ListAppender<>();
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;
    private static MessageListenerContainer consumer;
    private static long product;

    @BeforeAll
    static void start() {
        SALES.start();
        REDIS.start();
        KAFKA.start();
        app = new SpringApplicationBuilder(LimitedSaleApplication.class).run("--server.port=0",
            "--management.server.port=0", "--limited-sale.order.rate-limit.enabled=false",
            "--spring.config.additional-location=file:src/main/resources/application.yml",
            "--spring.profiles.active=test", "--limited-sale.recovery.enabled=false", "--spring.flyway.enabled=true",
            "--spring.jpa.hibernate.ddl-auto=validate", "--MOCK_PG_API_KEY=test-secret",
            "--spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration",
            "--spring.datasource.url=" + SALES.getJdbcUrl(), "--spring.datasource.username=" + SALES.getUsername(),
            "--spring.datasource.password=" + SALES.getPassword(), "--spring.data.redis.host=" + REDIS.getHost(),
            "--spring.data.redis.port=" + REDIS.getMappedPort(6379), "--spring.data.redis.timeout=1s",
            "--limited-sale.order.async.enabled=true", "--limited-sale.order.outbox-publisher.enabled=true",
            "--limited-sale.order.consumer.enabled=true", "--limited-sale.kafka.topic-provisioning.enabled=true",
            "--limited-sale.observability.backlog-interval=PT1S",
            "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers());
        jdbc = app.getBean(JdbcTemplate.class);
        product = app.getBean(ProductCommand.class).register("observed product", "", 1000L).productId();
        consumer = app.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("order-acceptance-consumer");
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
            assertThat(consumer.getAssignedPartitions()).hasSize(6));
        // Attached after startup, which resets the logging configuration.
        Logger listenerLog = (Logger) LoggerFactory.getLogger(
            "com.wooyongjeong.limitedsale.order.infrastructure.messaging.OrderAcceptanceListener");
        listenerLog.setLevel(Level.DEBUG);
        LISTENER_LOG.start();
        listenerLog.addAppender(LISTENER_LOG);
    }

    @AfterEach
    void everythingRunsAgain() {
        if (!consumer.isRunning()) {
            consumer.start();
        }
        var publisher = app.getBean(OrderOutboxPublisher.class);
        if (!publisher.isRunning()) {
            publisher.start();
        }
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
        KAFKA.stop();
        REDIS.stop();
        SALES.stop();
    }

    private static double gauge(String name) {
        var gauge = app.getBean(MeterRegistry.class).find(name).gauge();
        return gauge == null ? Double.NaN : gauge.value();
    }

    private static long timerCount(String name, String result) {
        var search = app.getBean(MeterRegistry.class).find(name);
        if (result != null) {
            search = search.tag("result", result);
        }
        var timer = search.timer();
        return timer == null ? 0 : timer.count();
    }

    private static String scrape() throws Exception {
        int port = Integer.parseInt(app.getEnvironment().getProperty("local.management.port"));
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
            + "/actuator/prometheus")).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString())
            .body();
    }

    private static long sale(boolean stopped) {
        jdbc.update("INSERT INTO sales(product_id,name,description,product_name_snapshot,unit_price,starts_at,ends_at,"
            + "total_quantity,per_member_limit,is_stopped,created_at,updated_at) VALUES (?, 'sale', '', 'product', "
            + "1000, UTC_TIMESTAMP(6) - INTERVAL 1 HOUR, UTC_TIMESTAMP(6) + INTERVAL 1 HOUR, 100, 100, ?, "
            + "UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", product, stopped);
        return jdbc.queryForObject("SELECT MAX(id) FROM sales", Long.class);
    }

    private static Client member() throws Exception {
        String email = "observed" + MEMBERS.incrementAndGet() + "@example.com";
        app.getBean(MemberService.class).register(email, "password", "observed" + MEMBERS.get());
        return new Client(((ServletWebServerApplicationContext) app).getWebServer().getPort(), email).login();
    }

    private static String status(String acceptanceId) {
        return jdbc.queryForObject("SELECT status FROM order_acceptances WHERE public_id = UNHEX(REPLACE(?, '-', ''))",
            String.class, acceptanceId);
    }

    private static final class Client {
        final int port;
        final String email;
        final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String token;

        Client(int port, String email) {
            this.port = port;
            this.email = email;
        }

        HttpResponse<String> request(String method, String path, String body, String key, String requestId)
                throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15));
            if (token != null) {
                builder.header("X-CSRF-TOKEN", token);
            }
            if (key != null) {
                builder.header("Idempotency-Key", key);
            }
            if (requestId != null) {
                builder.header("X-Request-Id", requestId);
            }
            if (body != null) {
                builder.header("Content-Type", "application/json");
            }
            return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }

        Client login() throws Exception {
            token = csrf();
            assertThat(request("POST", "/api/v1/sessions", "{\"email\":\"" + email
                + "\",\"password\":\"password\"}", null, null).statusCode()).isEqualTo(200);
            token = csrf();
            return this;
        }

        private String csrf() throws Exception {
            String saved = token;
            token = null;
            String fresh = JSON.readTree(request("GET", "/api/v1/csrf", null, null, null).body()).path("data")
                .path("token").asString();
            token = saved;
            return fresh;
        }

        HttpResponse<String> send(long saleId, String requestId) throws Exception {
            return request("POST", "/api/v2/orders", "{\"saleId\":" + saleId + ",\"quantity\":1}",
                UUID.randomUUID().toString(), requestId);
        }

        String accept(long saleId, String requestId) throws Exception {
            var response = send(saleId, requestId);
            assertThat(response.statusCode()).isEqualTo(202);
            return JSON.readTree(response.body()).path("data").path("acceptanceId").asString();
        }
    }

    @Test
    void whileTheConsumerIsStoppedAcceptedRequestsPileUpAndAfterItResumesTheyDrainAndAreTimed() throws Exception {
        long saleId = sale(false);
        Client client = member();
        long created = timerCount("order.acceptance.completion", "order_created");
        long published = timerCount("order.outbox.publish.delay", null);
        consumer.stop();
        await().atMost(Duration.ofSeconds(30)).until(() -> !consumer.isRunning());

        List<String> accepted = new ArrayList<>();
        for (int count = 0; count < 5; count++) {
            accepted.add(client.accept(saleId, null));
        }

        // Published (the publisher still runs) but not consumed: the backlog is in the acceptances, not the outbox.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(gauge("order.acceptance.accepted")).isEqualTo(5);
            assertThat(gauge("order.outbox.unpublished")).isZero();
        });
        double age = gauge("order.acceptance.oldest.accepted");
        Thread.sleep(2500);
        assertThat(gauge("order.acceptance.oldest.accepted")).isGreaterThan(age).isGreaterThanOrEqualTo(2);
        assertThat(timerCount("order.acceptance.completion", "order_created")).isEqualTo(created);

        consumer.start();

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(gauge("order.acceptance.accepted")).isZero();
            assertThat(gauge("order.acceptance.oldest.accepted")).isZero();
        });
        assertThat(accepted).allSatisfy(id -> assertThat(status(id)).isEqualTo("ORDER_CREATED"));
        assertThat(timerCount("order.acceptance.completion", "order_created")).isEqualTo(created + 5);
        assertThat(timerCount("order.outbox.publish.delay", null)).isEqualTo(published + 5);
        // They waited at least the time the consumer was away.
        var completion = app.getBean(MeterRegistry.class).find("order.acceptance.completion")
            .tag("result", "order_created").timer();
        assertThat(completion.max(java.util.concurrent.TimeUnit.SECONDS)).isGreaterThanOrEqualTo(2);
        assertThat(scrape()).contains("order_acceptance_completion_seconds_bucket{result=\"order_created\"")
            .contains("order_outbox_publish_delay_seconds_bucket").contains("order_acceptance_accepted ")
            .contains("order_acceptance_oldest_accepted_seconds ").contains("order_outbox_unpublished ")
            .contains("order_outbox_oldest_unpublished_seconds ");
    }

    @Test
    void whileThePublisherIsStoppedOutboxRowsPileUpAndAfterItResumesTheyAreSent() throws Exception {
        long saleId = sale(false);
        Client client = member();
        var publisher = app.getBean(OrderOutboxPublisher.class);
        publisher.stop();

        List<String> accepted = new ArrayList<>();
        for (int count = 0; count < 3; count++) {
            accepted.add(client.accept(saleId, null));
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(gauge("order.outbox.unpublished")).isEqualTo(3);
            assertThat(gauge("order.outbox.oldest.unpublished")).isPositive();
            assertThat(gauge("order.acceptance.accepted")).isEqualTo(3);
        });

        publisher.start();

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(gauge("order.outbox.unpublished")).isZero();
            assertThat(gauge("order.outbox.oldest.unpublished")).isZero();
            assertThat(gauge("order.acceptance.accepted")).isZero();
        });
        assertThat(accepted).allSatisfy(id -> assertThat(status(id)).isEqualTo("ORDER_CREATED"));
    }

    @Test
    void theHttpRequestIdIsStoredWithTheAcceptanceAndCarriedIntoTheConsumerLog() throws Exception {
        long saleId = sale(false);
        long stoppedSale = sale(true);
        Client client = member();

        String acceptanceId = client.accept(saleId, "trace-37-observe");
        var rejected = client.send(stoppedSale, "trace-37-rejected");

        assertThat(rejected.statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT request_id FROM order_acceptances WHERE public_id = "
            + "UNHEX(REPLACE(?, '-', ''))", String.class, acceptanceId)).isEqualTo("trace-37-observe");
        assertThat(jdbc.queryForObject("SELECT request_id FROM order_acceptances WHERE sale_id = ?", String.class,
            stoppedSale)).isEqualTo("trace-37-rejected");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(LISTENER_LOG.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage()).contains(acceptanceId).contains("ORDER_CREATED");
            assertThat(event.getMDCPropertyMap()).containsEntry("requestId", "trace-37-observe");
        }));
    }
}
