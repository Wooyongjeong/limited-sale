package com.wooyongjeong.mockpg;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentHttpIntegrationTest {
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
    TestRestTemplate http;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    org.springframework.context.ApplicationContext applicationContext;

    @Test
    void chargeIsIdempotentAndRejectsDifferentAmount() {
        UUID key = UUID.randomUUID();
        var first = call(HttpMethod.POST, "/api/v1/payments", "{\"pgKey\":\"" + key + "\",\"amount\":100}");
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getBody()).contains("SUCCEEDED");
        assertThat(call(HttpMethod.POST, "/api/v1/payments", "{\"pgKey\":\"" + key + "\",\"amount\":100}")
                .getStatusCode().value()).isEqualTo(200);
        assertThat(call(HttpMethod.POST, "/api/v1/payments", "{\"pgKey\":\"" + key + "\",\"amount\":101}")
                .getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void closeUnknownFencesLateChargeAndMissingQueryIs404() {
        UUID key = UUID.randomUUID();
        assertThat(call(HttpMethod.GET, "/api/v1/payments/" + key, null).getStatusCode().value()).isEqualTo(404);
        assertThat(call(HttpMethod.POST, "/api/v1/payments/" + key + "/close", "{\"amount\":5}").getBody())
                .contains("CLOSED");
        assertThat(call(HttpMethod.POST, "/api/v1/payments", charge(key, 5)).getBody()).contains("CLOSED");
    }

    @Test
    void settingsAreSeparateFrozenAndDefaultsAreNormalized() {
        UUID key = UUID.randomUUID();
        assertThat(configure(key, "{\"scenario\":\"DELAY\"}").getStatusCode().value()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/api/v1/payments/" + key, null).getStatusCode().value()).isEqualTo(404);
        assertThat(call(HttpMethod.POST, "/api/v1/payments", charge(key, 7)).getStatusCode().value()).isEqualTo(202);
        assertThat(configure(key, "{\"scenario\":\"DELAY\",\"delaySeconds\":10}")
                .getStatusCode().value()).isEqualTo(200);
        assertThat(configure(key, "{\"scenario\":\"SUCCESS\"}").getStatusCode().value()).isEqualTo(409);
        assertThat(call(HttpMethod.POST, "/api/v1/payments/" + key + "/close", "{\"amount\":7}").getBody())
                .contains("CLOSED");
    }

    @Test
    void delayResolvesAfterDueEvenWhenClosing() {
        UUID key = UUID.randomUUID();
        configure(key, "{\"scenario\":\"DELAY\"}");
        assertThat(call(HttpMethod.POST, "/api/v1/payments", charge(key, 8)).getStatusCode().value()).isEqualTo(202);
        jdbc.update("UPDATE mock_payments SET completion_due_at = UTC_TIMESTAMP(6) - INTERVAL 1 MICROSECOND "
                + "WHERE pg_key = UNHEX(REPLACE(?, '-', ''))", key.toString());
        assertThat(call(HttpMethod.POST, "/api/v1/payments/" + key + "/close", "{\"amount\":8}").getBody())
                .contains("SUCCEEDED");
    }

    @Test
    void declineIsFinalAndCloseKeepsFailure() {
        UUID key = UUID.randomUUID();
        configure(key, "{\"scenario\":\"DECLINE\"}");
        assertThat(call(HttpMethod.POST, "/api/v1/payments", charge(key, 8)).getBody()).contains("FAILED");
        assertThat(call(HttpMethod.POST, "/api/v1/payments/" + key + "/close", "{\"amount\":8}").getBody())
                .contains("FAILED");
    }

    @Test
    void responseDelayDoesNotHoldLocksOrRepeat() throws Exception {
        UUID key = UUID.randomUUID();
        configure(key, "{\"scenario\":\"RESPONSE_LOSS\",\"delaySeconds\":3}");
        var first = CompletableFuture.supplyAsync(() -> call(HttpMethod.POST, "/api/v1/payments", charge(key, 9)));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && jdbc.queryForObject("SELECT COUNT(*) FROM mock_payments "
                + "WHERE pg_key=UNHEX(REPLACE(?, '-', '')) AND response_loss_consumed=1", Integer.class,
                key.toString()) == 0) {
            Thread.sleep(10);
        }
        assertThat(first.isDone()).isFalse();
        assertThat(call(HttpMethod.GET, "/api/v1/payments/" + key, null).getBody()).contains("SUCCEEDED");
        assertThat(call(HttpMethod.POST, "/api/v1/payments", charge(key, 9)).getBody()).contains("SUCCEEDED");
        assertThat(first.isDone()).isFalse();
        assertThat(first.get(5, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void simultaneousRequestsWithDifferentAmountsProduceOneResult() {
        UUID key = UUID.randomUUID();
        var gate = new java.util.concurrent.CountDownLatch(1);
        var tasks = new ArrayList<CompletableFuture<Integer>>();
        for (int i = 0; i < 12; i++) {
            int amount = i % 2 + 10;
            tasks.add(CompletableFuture.supplyAsync(() -> {
                try {
                    gate.await();
                    return call(HttpMethod.POST, "/api/v1/payments", charge(key, amount)).getStatusCode().value();
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }));
        }
        gate.countDown();
        var statuses = tasks.stream().map(CompletableFuture::join).toList();
        assertThat(statuses).containsOnly(200, 409).contains(200, 409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_payments WHERE pg_key=UNHEX(REPLACE(?, '-', ''))",
                Integer.class, key.toString())).isEqualTo(1);
    }

    @Test
    void authenticationAndStrictJsonAreEnforced() {
        assertThat(http.getForEntity("/api/v1/payments/" + UUID.randomUUID(), String.class).getStatusCode().value())
                .isEqualTo(401);
        assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode().value()).isEqualTo(200);
        HttpHeaders incorrect = new HttpHeaders();
        incorrect.set("X-Mock-PG-Key", "incorrect");
        assertThat(http.exchange("/api/v1/payments/" + UUID.randomUUID(), HttpMethod.GET,
                new HttpEntity<Void>(incorrect), String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.GET, "/api/v1/payments/not-a-uuid", null).getStatusCode().value()).isEqualTo(400);
        UUID key = UUID.randomUUID();
        String[] invalid = {"null", "{}", "{\"pgKey\":\"" + key + "\",\"amount\":1,\"amount\":2}",
                "{\"pgKey\":\"" + key + "\",\"amount\":1,\"x\":2}",
                "{\"pgKey\":\"" + key + "\",\"amount\":1} {}",
                "{\"pgKey\":\"" + key + "\",\"amount\":1.0}",
                "{\"pgKey\":\"" + key + "\",\"amount\":null}"};
        for (String json : invalid) {
            assertThat(call(HttpMethod.POST, "/api/v1/payments", json).getStatusCode().value()).isEqualTo(400);
        }
        assertThat(configure(key, "{\"scenario\":\"SUCCESS\",\"delaySeconds\":1}")
                .getStatusCode().value()).isEqualTo(400);
        for (String invalidSimulation : java.util.List.of("{}", "{\"scenario\":null}",
                "{\"scenario\":\"unknown\"}", "{\"scenario\":\"DELAY\",\"delaySeconds\":null}",
                "{\"scenario\":\"DELAY\",\"delaySeconds\":0}",
                "{\"scenario\":\"RESPONSE_LOSS\",\"delaySeconds\":601}",
                "{\"scenario\":\"DELAY\",\"delaySeconds\":\"1\"}")) {
            assertThat(configure(key, invalidSimulation).getStatusCode().value()).isEqualTo(400);
        }
        for (String invalidAmount : java.util.List.of("0", "1000000000000001", "-1", "\"1\"", "true")) {
            assertThat(call(HttpMethod.POST, "/api/v1/payments",
                    "{\"pgKey\":\"" + key + "\",\"amount\":" + invalidAmount + "}")
                    .getStatusCode().value()).isEqualTo(400);
        }
        assertThat(call(HttpMethod.POST, "/api/v1/payments", charge(UUID.randomUUID(), 1_000_000_000_000_000L))
                .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void unauthenticatedPathVariantsCannotReachAnyBusinessEndpoint() throws Exception {
        UUID key = UUID.randomUUID();
        int port = ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext) applicationContext)
                .getWebServer().getPort();
        var client = java.net.http.HttpClient.newHttpClient();
        for (String prefix : java.util.List.of("/api/v1", "/api;probe/v1", "/api/v1;probe", "/%61pi/v1",
                "/api%3Bprobe/v1", "/api%2fv1", "/api//v1", "/api/v1/../v1")) {
            String base = "http://localhost:" + port + prefix;
            for (String[] operation : java.util.List.of(
                    new String[] {"POST", "/payments", charge(key, 20)},
                    new String[] {"GET", "/payments/" + key, ""},
                    new String[] {"POST", "/payments/" + key + "/close", "{\"amount\":20}"},
                    new String[] {"PUT", "/simulations/" + key, "{\"scenario\":\"DECLINE\"}"})) {
                var body = operation[0].equals("GET") ? java.net.http.HttpRequest.BodyPublishers.noBody()
                        : java.net.http.HttpRequest.BodyPublishers.ofString(operation[2]);
                var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + operation[1]))
                        .header("Content-Type", "application/json").method(operation[0], body).build();
                int status = client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
                assertThat(status).as("%s %s", operation[0], base + operation[1]).isIn(400, 401);
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_payments WHERE pg_key=UNHEX(REPLACE(?, '-', ''))",
                Integer.class, key.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_scenario_settings "
                + "WHERE pg_key=UNHEX(REPLACE(?, '-', ''))", Integer.class, key.toString())).isZero();
        String health = "http://localhost:" + port + "/actuator/health";
        for (String method : java.util.List.of("GET", "HEAD")) {
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(health))
                    .method(method, java.net.http.HttpRequest.BodyPublishers.noBody()).build();
            assertThat(client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode())
                    .isEqualTo(200);
        }
        var disguisedHealth = java.net.http.HttpRequest.newBuilder(java.net.URI.create(health + ";probe"))
                .GET().build();
        assertThat(client.send(disguisedHealth, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode())
                .isIn(400, 401);
        assertThat(call(HttpMethod.POST, "/api/v1/payments", charge(key, 20)).getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_payments WHERE pg_key=UNHEX(REPLACE(?, '-', ''))",
                Integer.class, key.toString())).isEqualTo(1);
    }

    @Test
    void lockWaitCrossingDueUsesFreshDatabaseTime() throws Exception {
        UUID key = UUID.randomUUID();
        configure(key, "{\"scenario\":\"DELAY\"}");
        call(HttpMethod.POST, "/api/v1/payments", charge(key, 11));
        jdbc.update("UPDATE mock_payments SET completion_due_at=UTC_TIMESTAMP(6) + INTERVAL 2 SECOND "
                + "WHERE pg_key=UNHEX(REPLACE(?, '-', ''))", key.toString());
        try (var connection = java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword())) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT pg_key FROM mock_payments "
                    + "WHERE pg_key=UNHEX(REPLACE(?, '-', '')) FOR UPDATE")) {
                statement.setString(1, key.toString());
                statement.executeQuery().close();
            }
            var waiting = CompletableFuture.supplyAsync(() -> call(HttpMethod.POST,
                    "/api/v1/payments/" + key + "/close", "{\"amount\":11}"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean observedWait = false;
            while (System.nanoTime() < deadline && !observedWait) {
                observedWait = jdbc.queryForList("SHOW FULL PROCESSLIST").stream().anyMatch(row -> {
                    Object info = row.get("Info");
                    return info != null && info.toString().toLowerCase().contains("mock_payments")
                            && info.toString().toLowerCase().contains("for update");
                });
                if (!observedWait) {
                    Thread.sleep(10);
                }
            }
            assertThat(observedWait).as("observed payment row lock wait").isTrue();
            while (jdbc.queryForObject("SELECT UTC_TIMESTAMP(6) < completion_due_at FROM mock_payments "
                    + "WHERE pg_key=UNHEX(REPLACE(?, '-', ''))", Boolean.class, key.toString())) {
                Thread.sleep(10);
            }
            connection.commit();
            assertThat(waiting.get(5, TimeUnit.SECONDS).getBody()).contains("SUCCEEDED");
        }
    }

    @Test
    void settingsAndConsumedResponseDelaySurviveApplicationRestart() throws Exception {
        UUID key = UUID.randomUUID();
        UUID delayedKey = UUID.randomUUID();
        try (var first = newContext()) {
            int port = ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext) first)
                    .getWebServer().getPort();
            String base = "http://localhost:" + port;
            assertThat(call(HttpMethod.PUT, base + "/api/v1/simulations/" + key,
                    "{\"scenario\":\"RESPONSE_LOSS\",\"delaySeconds\":1}").getStatusCode().value()).isEqualTo(200);
            assertThat(call(HttpMethod.POST, base + "/api/v1/payments", charge(key, 12)).getBody())
                    .contains("SUCCEEDED");
            assertThat(call(HttpMethod.PUT, base + "/api/v1/simulations/" + delayedKey,
                    "{\"scenario\":\"DELAY\",\"delaySeconds\":600}").getStatusCode().value()).isEqualTo(200);
            assertThat(call(HttpMethod.POST, base + "/api/v1/payments", charge(delayedKey, 14))
                    .getStatusCode().value()).isEqualTo(202);
        }
        try (var restarted = newContext()) {
            int port = ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext) restarted)
                    .getWebServer().getPort();
            String base = "http://localhost:" + port;
            assertThat(call(HttpMethod.GET, base + "/api/v1/payments/" + key, null).getBody()).contains("SUCCEEDED");
            assertThat(call(HttpMethod.GET, base + "/api/v1/payments/" + delayedKey, null).getStatusCode().value())
                    .isEqualTo(202);
            jdbc.update("UPDATE mock_payments SET completion_due_at=UTC_TIMESTAMP(6)-INTERVAL 1 MICROSECOND "
                    + "WHERE pg_key=UNHEX(REPLACE(?, '-', ''))", delayedKey.toString());
            assertThat(call(HttpMethod.GET, base + "/api/v1/payments/" + delayedKey, null).getBody())
                    .contains("SUCCEEDED");
            assertThat(call(HttpMethod.PUT, base + "/api/v1/simulations/" + key,
                    "{\"scenario\":\"RESPONSE_LOSS\",\"delaySeconds\":1}").getStatusCode().value()).isEqualTo(200);
            assertThat(call(HttpMethod.PUT, base + "/api/v1/simulations/" + key,
                    "{\"scenario\":\"SUCCESS\"}").getStatusCode().value()).isEqualTo(409);
            long start = System.nanoTime();
            assertThat(call(HttpMethod.POST, base + "/api/v1/payments", charge(key, 12)).getBody())
                    .contains("SUCCEEDED");
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(900);
        }
    }

    private org.springframework.context.ConfigurableApplicationContext newContext() {
        return new org.springframework.boot.builder.SpringApplicationBuilder(MockPgApplication.class)
                .run("--server.port=0", "--mock-pg.api-key=integration-test-key",
                        "--spring.datasource.url=" + MYSQL.getJdbcUrl()
                                + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                        "--spring.datasource.username=" + MYSQL.getUsername(),
                        "--spring.datasource.password=" + MYSQL.getPassword());
    }

    @Test
    void chargeCloseAndConfigurationRaceConverges() throws Exception {
        UUID key = UUID.randomUUID();
        var gate = new java.util.concurrent.CountDownLatch(1);
        var charge = CompletableFuture.supplyAsync(() -> {
            await(gate);
            return call(HttpMethod.POST, "/api/v1/payments", charge(key, 13));
        });
        var close = CompletableFuture.supplyAsync(() -> {
            await(gate);
            return call(HttpMethod.POST, "/api/v1/payments/" + key + "/close", "{\"amount\":13}");
        });
        var setting = CompletableFuture.supplyAsync(() -> {
            await(gate);
            return configure(key, "{\"scenario\":\"DECLINE\"}");
        });
        gate.countDown();
        var chargeResult = charge.get(10, TimeUnit.SECONDS);
        var closeResult = close.get(10, TimeUnit.SECONDS);
        var settingResult = setting.get(10, TimeUnit.SECONDS);
        assertThat(chargeResult.getStatusCode().value()).isEqualTo(200);
        assertThat(closeResult.getStatusCode().value()).isEqualTo(200);
        assertThat(settingResult.getStatusCode().value()).isIn(200, 409);
        assertThat(chargeResult.getBody()).isEqualTo(closeResult.getBody());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_payments WHERE pg_key=UNHEX(REPLACE(?, '-', ''))",
                Integer.class, key.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_scenario_settings s JOIN mock_payments p "
                + "ON s.pg_key=p.pg_key AND s.scenario=p.scenario WHERE s.pg_key=UNHEX(REPLACE(?, '-', ''))",
                Integer.class, key.toString())).isEqualTo(1);
    }

    @Test
    void intentionalResponseDelayCausesShortClientTimeoutOnlyOnce() throws Exception {
        UUID key = UUID.randomUUID();
        configure(key, "{\"scenario\":\"RESPONSE_LOSS\",\"delaySeconds\":2}");
        int port = ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext) applicationContext).getWebServer().getPort();
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port
                + "/api/v1/payments")).timeout(java.time.Duration.ofMillis(500)).header("X-Mock-PG-Key", "integration-test-key")
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(charge(key, 15))).build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> java.net.http.HttpClient.newHttpClient()
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(java.net.http.HttpTimeoutException.class);
        assertThat(call(HttpMethod.POST, "/api/v1/payments", charge(key, 15)).getBody()).contains("SUCCEEDED");
    }

    private void await(java.util.concurrent.CountDownLatch gate) {
        try {
            gate.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void databaseRejectsMissingDelayForDelayedScenario() {
        UUID key = UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO mock_scenario_settings(pg_key, scenario, delay_seconds) "
                        + "VALUES (UNHEX(REPLACE(?, '-', '')), 'DELAY', NULL)", key.toString()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("ck_mock_settings_delay");
    }

    private String charge(UUID key, long amount) {
        return "{\"pgKey\":\"" + key + "\",\"amount\":" + amount + "}";
    }

    private org.springframework.http.ResponseEntity<String> configure(UUID key, String body) {
        return call(HttpMethod.PUT, "/api/v1/simulations/" + key, body);
    }

    private org.springframework.http.ResponseEntity<String> call(HttpMethod method, String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Mock-PG-Key", "integration-test-key");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }
}
