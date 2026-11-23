package com.wooyongjeong.limitedsale.common;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.wooyongjeong.limitedsale.LimitedSaleApplication;
import com.wooyongjeong.limitedsale.member.application.MemberService;
import com.wooyongjeong.limitedsale.member.presentation.code.AuthenticationResponseCode;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;

import static com.wooyongjeong.limitedsale.support.ResponseEnvelope.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs the app with the real application.yml and no Redis overrides, so the timeouts and key namespace asserted
 * here are the ones that ship. Redis runs without maxmemory because the memory policy belongs to compose.yaml.
 */
class RedisConnectionIntegrationTest {
    private static final MySQLContainer DB = new MySQLContainer("mysql:8.4.7");
    private static final int REDIS_PORT = freePort();
    // A fixed host port keeps the address valid across stop/start; a random mapping may change on restart.
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379)
            .withCreateContainerCmdModifier(command -> command.withHostConfig(command.getHostConfig()
                    .withPortBindings(new PortBinding(Ports.Binding.bindPort(REDIS_PORT), new ExposedPort(6379)))));
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static ConfigurableApplicationContext context;
    private static int port;
    private static int managementPort;

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @BeforeAll
    static void start() {
        DB.start();
        REDIS.start();
        // mock-pg's application.yml shadows the sales one on this classpath, so load the real one explicitly.
        context = new SpringApplicationBuilder(LimitedSaleApplication.class).run("--server.port=0",
                "--spring.config.additional-location=file:src/main/resources/application.yml",
                "--limited-sale.recovery.enabled=false",
                "--spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration",
                "--spring.datasource.url=" + DB.getJdbcUrl(), "--spring.datasource.username=" + DB.getUsername(),
                "--spring.datasource.password=" + DB.getPassword(),
                "--spring.data.redis.host=" + REDIS.getHost(), "--spring.data.redis.port=" + REDIS_PORT);
        port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        managementPort = Integer.parseInt(context.getEnvironment().getProperty("local.management.port"));
        context.getBean(MemberService.class).register("redis-conn@example.com", "password", "redisconn");
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
        REDIS.stop();
        DB.stop();
    }

    private static class Client {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
        String token;

        HttpResponse<String> send(String method, String path, String body) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(30));
            if (token != null) {
                builder.header("X-CSRF-TOKEN", token);
            }
            if (body != null) {
                builder.header("Content-Type", "application/json");
            }
            builder.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> login(String password) throws Exception {
            token = null;
            var csrf = send("GET", "/api/v1/csrf", null);
            assertCode(csrf, AuthenticationResponseCode.CSRF_TOKEN_ISSUED);
            token = JSON.readTree(csrf.body()).path("data").path("token").asString();
            return send("POST", "/api/v1/sessions",
                    "{\"email\":\"redis-conn@example.com\",\"password\":\"" + password + "\"}");
        }
    }

    private static List<String> redisKeys(String pattern) throws Exception {
        String output = REDIS.execInContainer("redis-cli", "--scan", "--pattern", pattern).getStdout();
        return Arrays.stream(output.split("\\R")).filter(line -> !line.isBlank()).toList();
    }

    private static String managementStatus() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/health"))
                .timeout(Duration.ofSeconds(30)).build();
        JsonNode body = JSON.readTree(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body());
        return body.path("status").asString();
    }

    @Test
    void sessionAndLoginFailureKeysLiveUnderTheAppPrefix() throws Exception {
        Client client = new Client();
        assertThat(client.login("wrong-password").statusCode()).isEqualTo(401);
        // A successful login clears the failure record, so check it while the failure is still counted.
        assertThat(redisKeys("limited-sale:login-failure:*")).isNotEmpty();
        assertThat(client.login("password").statusCode()).isEqualTo(200);

        assertThat(redisKeys("limited-sale:session:*")).isNotEmpty();
        assertThat(redisKeys("spring:session*")).isEmpty();
        // Every key the app wrote sits under one prefix, so SCAN and memory analysis can address all of them.
        assertThat(redisKeys("*")).allMatch(key -> key.startsWith("limited-sale:"));
    }

    @Test
    void shippedTimeoutsAreTwoSecondsNotLettuceDefault() {
        assertThat(context.getEnvironment().getProperty("spring.data.redis.timeout", Duration.class))
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(context.getEnvironment().getProperty("spring.data.redis.connect-timeout", Duration.class))
                .isEqualTo(Duration.ofSeconds(2));

        var configuration = context.getBean(LettuceConnectionFactory.class).getClientConfiguration();
        assertThat(configuration.getCommandTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(configuration.getClientOptions().orElseThrow().getSocketOptions().getConnectTimeout())
                .isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void unresponsiveRedisEndsAuthenticatedRequestWith503NearTheTimeout() throws Exception {
        Client client = new Client();
        assertThat(client.login("password").statusCode()).isEqualTo(200);
        assertThat(client.send("GET", "/api/v1/members/me", null).statusCode()).isEqualTo(200);

        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            long begin = System.nanoTime();
            var response = client.send("GET", "/api/v1/members/me", null);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - begin);

            assertCode(response, AuthenticationResponseCode.AUTHENTICATION_UNAVAILABLE);
            // A hung Redis is only given up on after the command timeout, and far sooner than Lettuce's 60 s default.
            assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(1500)).isLessThan(Duration.ofSeconds(10));
        } finally {
            docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        assertThat(client.send("GET", "/api/v1/members/me", null).statusCode()).isEqualTo(200);
    }

    @Test
    void appReconnectsAfterRedisRestartWithoutRestartingTheApp() throws Exception {
        Client before = new Client();
        assertThat(before.login("password").statusCode()).isEqualTo(200);
        assertThat(managementStatus()).isEqualTo("UP");

        var docker = DockerClientFactory.instance().client();
        docker.stopContainerCmd(REDIS.getContainerId()).exec();
        try {
            assertThat(managementStatus()).isNotEqualTo("UP");
            assertCode(before.send("GET", "/api/v1/members/me", null),
                    AuthenticationResponseCode.AUTHENTICATION_UNAVAILABLE);
        } finally {
            docker.startContainerCmd(REDIS.getContainerId()).exec();
        }

        // Lettuce reconnects with backoff, so allow it more than the command timeout.
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(managementStatus()).isEqualTo("UP"));
        Client after = new Client();
        assertThat(after.login("password").statusCode()).isEqualTo(200);
        assertThat(after.send("GET", "/api/v1/members/me", null).statusCode()).isEqualTo(200);
        assertThat(redisKeys("limited-sale:session:*")).isNotEmpty();
    }

    @Test
    void lettuceCommandLatencyIsExposedWithoutHighCardinalityLabels() throws Exception {
        Client client = new Client();
        assertThat(client.login("password").statusCode()).isEqualTo(200);
        assertThat(client.send("GET", "/api/v1/members/me", null).statusCode()).isEqualTo(200);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus"))
                .build();
        String scrape = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
        var samples = scrape.lines().filter(line -> line.startsWith("lettuce_seconds_")).toList();

        assertThat(samples).anyMatch(line -> line.startsWith("lettuce_seconds_count{")
                && line.contains("db_operation=\"HGETALL\"") && line.contains("error=\"none\""));
        // The labels name the command and the server, never a member, e-mail or session.
        var labelNames = samples.stream().map(line -> line.substring(line.indexOf('{') + 1, line.indexOf('}')))
                .flatMap(labels -> Arrays.stream(labels.split(",(?=[a-z_]+=\")")))
                .map(label -> label.substring(0, label.indexOf('='))).distinct().toList();
        assertThat(labelNames).containsExactlyInAnyOrder(
                "db_operation", "db_system", "error", "net_sock_peer_addr", "net_sock_peer_port", "net_transport");
        assertThat(String.join("\n", samples)).doesNotContain("redis-conn@example.com", "session");
    }
}
