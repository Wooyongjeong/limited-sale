package com.wooyongjeong.limitedsale.common;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wooyongjeong.limitedsale.LimitedSaleApplication;
import com.wooyongjeong.limitedsale.member.application.MemberService;
import com.wooyongjeong.limitedsale.order.infrastructure.messaging.OrderAcceptanceTopicConfiguration;
import com.wooyongjeong.limitedsale.product.api.ProductCommand;
import com.wooyongjeong.limitedsale.support.FixedPortKafka;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Starts the app with the real application.yml and nothing listening at the Kafka address: it must start, keep
 * serving v1 orders, report the failed topic creation as a warning, and create the topic once a broker appears.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KafkaAbsentBrokerIntegrationTest {
    private static final String TOPIC = OrderAcceptanceTopicConfiguration.ORDER_ACCEPTANCES_TOPIC;
    private static final MySQLContainer DB = new MySQLContainer("mysql:8.4.7");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final int KAFKA_PORT = FixedPortKafka.freePort();
    private static final KafkaContainer KAFKA = FixedPortKafka.create(KAFKA_PORT);
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final ListAppender<ILoggingEvent> PROVISIONER_LOG = new ListAppender<>();
    private static Logger provisionerLogger;
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static int port;
    private static int managementPort;

    @BeforeAll
    static void start() {
        DB.start();
        REDIS.start();
        // KAFKA_PORT is reserved but nothing runs there yet.
        context = new SpringApplicationBuilder(LimitedSaleApplication.class).run("--server.port=0",
                "--spring.config.additional-location=file:src/main/resources/application.yml",
                "--limited-sale.recovery.enabled=false", "--limited-sale.order.rate-limit.enabled=false",
                "--limited-sale.kafka.topic-provisioning.enabled=true",
                "--spring.kafka.bootstrap-servers=localhost:" + KAFKA_PORT,
                "--spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration",
                "--spring.datasource.url=" + DB.getJdbcUrl(), "--spring.datasource.username=" + DB.getUsername(),
                "--spring.datasource.password=" + DB.getPassword(),
                "--spring.data.redis.host=" + REDIS.getHost(), "--spring.data.redis.port=" + REDIS.getMappedPort(6379));
        port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        managementPort = Integer.parseInt(context.getEnvironment().getProperty("local.management.port"));
        jdbc = context.getBean(JdbcTemplate.class);
        // Attached after startup: Spring Boot resets the logging configuration while the context starts, and the first
        // failed attempt is logged only after the admin client gave up on the missing broker.
        provisionerLogger = (Logger) LoggerFactory.getLogger(
                "com.wooyongjeong.limitedsale.common.messaging.KafkaTopicProvisioner");
        PROVISIONER_LOG.start();
        provisionerLogger.addAppender(PROVISIONER_LOG);
    }

    @AfterAll
    static void stop() {
        if (provisionerLogger != null) {
            provisionerLogger.detachAppender(PROVISIONER_LOG);
        }
        if (context != null) {
            context.close();
        }
        KAFKA.stop();
        REDIS.stop();
        DB.stop();
    }

    private static HttpResponse<String> send(HttpClient http, String method, String path, String body, String csrf,
            String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30));
        if (csrf != null) {
            builder.header("X-CSRF-TOKEN", csrf);
        }
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return http.send(builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String csrf(HttpClient http) throws Exception {
        return JSON.readTree(send(http, "GET", "/api/v1/csrf", null, null, null).body()).path("data").path("token")
                .asString();
    }

    @Test
    @Order(1)
    void appStartsWithoutBrokerAndLogsTheTopicFailureAsWarning() {
        // The context is already running; the provisioner keeps trying in the background and says so as a warning.
        // The warning is not an error: nothing about a missing broker may look like a failed start.
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(PROVISIONER_LOG.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage()).contains("Kafka topic creation failed");
                }));
        assertThat(PROVISIONER_LOG.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.ERROR));
    }

    @Test
    @Order(2)
    void v1OrderAndHealthDoNotNeedKafka() throws Exception {
        var members = context.getBean(MemberService.class);
        members.register("nokafka@example.com", "password", "nokafka");
        long product = context.getBean(ProductCommand.class).register("product", "", 1000L).productId();
        jdbc.update("INSERT INTO sales(product_id,name,description,product_name_snapshot,unit_price,starts_at,ends_at,"
                + "total_quantity,per_member_limit,created_at,updated_at) VALUES (?, 'sale', '', 'product', 1000, "
                + "UTC_TIMESTAMP(6)-INTERVAL 1 HOUR, UTC_TIMESTAMP(6)+INTERVAL 1 HOUR, 5, 2, "
                + "UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", product);
        long sale = jdbc.queryForObject("SELECT MAX(id) FROM sales", Long.class);

        HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        assertThat(send(http, "POST", "/api/v1/sessions", "{\"email\":\"nokafka@example.com\",\"password\":\"password\"}",
                csrf(http), null).statusCode()).isEqualTo(200);
        var order = send(http, "POST", "/api/v1/orders", "{\"saleId\":" + sale + ",\"quantity\":1}", csrf(http),
                UUID.randomUUID().toString());
        assertThat(order.statusCode()).isEqualTo(201);

        var health = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + managementPort + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(health.body()).contains("\"status\":\"UP\"");
    }

    @Test
    @Order(3)
    void topicIsCreatedWhenTheBrokerAppearsAfterStartup() {
        KAFKA.start();
        KafkaAdmin admin = context.getBean(KafkaAdmin.class);
        // The provisioner retries with a growing delay (at most 30 s), so allow a few attempts.
        await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions()
                .untilAsserted(() -> {
                    TopicDescription description = admin.describeTopics(TOPIC).get(TOPIC);
                    assertThat(description.partitions()).hasSize(6);
                });
    }
}
