package com.wooyongjeong.limitedsale.sale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wooyongjeong.limitedsale.LimitedSaleApplication;
import com.wooyongjeong.limitedsale.member.application.MemberService;
import com.wooyongjeong.limitedsale.product.api.ProductCommand;
import com.wooyongjeong.limitedsale.sale.api.SaleCommand;
import com.wooyongjeong.limitedsale.sale.api.SaleCreation;
import com.wooyongjeong.limitedsale.sale.api.SaleDetails;
import com.wooyongjeong.limitedsale.sale.api.SalePatch;
import com.wooyongjeong.limitedsale.sale.api.SaleQuery;
import com.wooyongjeong.limitedsale.sale.domain.SaleException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SaleIntegrationTest {
    private static final MySQLContainer<?> DB = new MySQLContainer<>("mysql:8.4.7")
        .withDatabaseName("sales").withUsername("sales_user").withPassword("test_password");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static SaleCommand commands;
    private static SaleQuery queries;
    private static int port;
    private static long product1;
    private static long product2;

    @BeforeAll
    static void start() {
        DB.start();
        REDIS.start();
        context = new SpringApplicationBuilder(LimitedSaleApplication.class).run("--server.port=0",
            "--spring.datasource.url=" + DB.getJdbcUrl(), "--spring.datasource.username=" + DB.getUsername(),
            "--spring.datasource.password=" + DB.getPassword(), "--spring.data.redis.host=" + REDIS.getHost(),
            "--spring.data.redis.port=" + REDIS.getMappedPort(6379), "--spring.data.redis.timeout=1s",
            "--spring.data.redis.connect-timeout=1s",
            "--spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration");
        port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        jdbc = context.getBean(JdbcTemplate.class);
        commands = context.getBean(SaleCommand.class);
        queries = context.getBean(SaleQuery.class);
        product1 = context.getBean(ProductCommand.class).register("original product", "", 1000L).productId();
        product2 = context.getBean(ProductCommand.class).register("replacement product", "", 9000L).productId();
        context.getBean(MemberService.class).initializeAdmin("sale-admin@example.com", "password", "saleadmin");
        context.getBean(MemberService.class).register("sale-user@example.com", "password", "saleuser");
    }

    @BeforeEach
    void resetSales() {
        jdbc.update("DELETE FROM sales");
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
        REDIS.stop();
        DB.stop();
    }

    static class Client {
        private final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        private String token;

        HttpResponse<String> request(String method, String path, String body, String csrf) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (csrf != null) {
                builder.header("X-CSRF-TOKEN", csrf);
            }
            if (body != null) {
                builder.header("Content-Type", "application/json");
            }
            return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }

        void csrf() throws Exception {
            token = data(request("GET", "/api/v1/csrf", null, null)).path("token").asText();
        }

        void login(String email) throws Exception {
            csrf();
            assertThat(request("POST", "/api/v1/sessions",
                "{\"email\":\"" + email + "\",\"password\":\"password\"}", token).statusCode()).isEqualTo(200);
            csrf();
        }
    }

    private static JsonNode data(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).path("data");
    }

    private static Set<String> fields(JsonNode node) {
        return java.util.stream.StreamSupport.stream(
            java.util.Spliterators.spliteratorUnknownSize(node.fieldNames(), 0), false).collect(Collectors.toSet());
    }

    private static Instant dbNow() {
        return Instant.parse(jdbc.queryForObject(
            "SELECT DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ')", String.class));
    }

    private static Instant future() {
        return Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);
    }

    private static String body(long productId) {
        Instant start = future();
        return JSON.createObjectNode().put("productId", productId).put("name", "  sale  ")
            .put("startsAt", start.toString()).put("endsAt", start.plusSeconds(3600).toString())
            .put("totalQuantity", 10).toString();
    }

    private static SaleDetails create() {
        Instant start = future();
        return commands.create(new SaleCreation(product1, "sale", "description", null,
            start, start.plusSeconds(3600), 10, null));
    }

    @Test
    void missingSaleOrderRequestIsStoredAndReplayed() throws Exception {
        Client client = new Client();
        client.login("sale-user@example.com");
        String key = java.util.UUID.randomUUID().toString();
        String body = "{\"saleId\":999999999,\"quantity\":1}";
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/orders"))
            .header("Content-Type", "application/json")
            .header("X-CSRF-TOKEN", client.token)
            .header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var first = client.http.send(request, HttpResponse.BodyHandlers.ofString());
        var replay = client.http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(first.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(first.body()).path("code").asText()).isEqualTo("SALE_NOT_FOUND");
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_request_results", Integer.class)).isEqualTo(1);
    }

    @Test
    void dbClockUsesUtcWithoutJdbcTimezoneShift() {
        Instant before = Instant.parse(jdbc.queryForObject(
            "SELECT DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ')", String.class));
        Instant actual = context.getBean(com.wooyongjeong.limitedsale.sale.domain.SaleStore.class).now();
        Instant after = Instant.parse(jdbc.queryForObject(
            "SELECT DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ')", String.class));
        assertThat(actual).isBetween(before, after);
    }

    @Test
    void nativeFilterTimeDoesNotShiftWithJvmTimezone() {
        Instant expected = Instant.parse("2030-01-01T00:00:00.123456Z");
        try (var manager = context.getBean(jakarta.persistence.EntityManagerFactory.class).createEntityManager()) {
            String actual = manager.unwrap(org.hibernate.Session.class).createNativeQuery(
                "SELECT DATE_FORMAT(CAST(:now AS DATETIME(6)), '%Y-%m-%dT%H:%i:%s.%fZ')", String.class)
                .setParameter("now", expected)
                .getSingleResult();
            assertThat(Instant.parse(actual)).isEqualTo(expected);
        }
    }

    @Test
    void httpContractsPermissionsDefaultsSnapshotsAndIdempotentOperations() throws Exception {
        Client admin = new Client();
        admin.login("sale-admin@example.com");
        Client user = new Client();
        user.login("sale-user@example.com");
        Client anonymous = new Client();
        anonymous.csrf();
        String payload = body(product1);
        String path = "/api/v1/admin/sales";
        assertThat(admin.request("POST", path, payload, null).statusCode()).isEqualTo(403);
        assertThat(user.request("POST", path, payload, user.token).statusCode()).isEqualTo(403);
        assertThat(anonymous.request("POST", path, payload, anonymous.token).statusCode()).isEqualTo(401);
        var created = admin.request("POST", path, payload, admin.token);
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode sale = data(created);
        long id = sale.path("saleId").asLong();
        assertThat(sale.path("name").asText()).isEqualTo("sale");
        assertThat(sale.path("description").asText()).isEmpty();
        assertThat(sale.path("unitPrice").asLong()).isEqualTo(1000);
        assertThat(sale.path("perMemberLimit").asInt()).isEqualTo(1);
        assertThat(sale.path("status").asText()).isEqualTo("SCHEDULED");
        assertThat(sale.path("version").asLong()).isZero();
        assertThat(fields(sale)).containsExactlyInAnyOrder("saleId", "productId", "name", "description",
            "productNameSnapshot", "unitPrice", "startsAt", "endsAt", "totalQuantity", "perMemberLimit",
            "status", "version", "createdAt", "updatedAt");
        assertThat(data(anonymous.request("GET", "/api/v1/sales/" + id, null, null))).isEqualTo(sale);
        jdbc.update("UPDATE products SET name = 'edited source', base_price = 2000 WHERE id = ?", product1);
        assertThat(queries.get(id).productNameSnapshot()).isEqualTo("original product");
        assertThat(queries.get(id).unitPrice()).isEqualTo(1000);
        jdbc.update("UPDATE products SET name = 'original product', base_price = 1000 WHERE id = ?", product1);
        JsonNode changed = data(admin.request("PATCH", path + "/" + id,
            "{\"version\":0,\"productId\":" + product2 + ",\"description\":null}", admin.token));
        assertThat(changed.path("productNameSnapshot").asText()).isEqualTo("replacement product");
        assertThat(changed.path("unitPrice").asLong()).isEqualTo(1000);
        assertThat(changed.path("version").asLong()).isEqualTo(1);
        assertThat(admin.request("PATCH", path + "/" + id, "{\"version\":0,\"name\":\"stale\"}", admin.token)
            .statusCode()).isEqualTo(409);
        JsonNode stopped = data(admin.request("POST", path + "/" + id + "/stop", null, admin.token));
        assertThat(stopped.path("status").asText()).isEqualTo("STOPPED");
        assertThat(data(admin.request("POST", path + "/" + id + "/stop", null, admin.token))).isEqualTo(stopped);
        assertThat(data(admin.request("POST", path + "/" + id + "/resume", null, admin.token))
            .path("status").asText()).isEqualTo("SCHEDULED");
        var deletion = admin.request("DELETE", path + "/" + id, null, admin.token);
        assertThat(deletion.statusCode()).isEqualTo(200);
        assertThat(data(deletion).isNull()).isTrue();
        assertThat(admin.request("DELETE", path + "/" + id, null, admin.token).statusCode()).isEqualTo(404);
    }

    @Test
    void stateFiltersUseConfirmedQuantitiesAndShareResponseTimeRules() throws Exception {
        SaleDetails scheduled = create();
        SaleDetails stopped = commands.stop(create().saleId());
        SaleDetails open = create();
        SaleDetails soldOut = create();
        SaleDetails ended = create();
        for (long id : List.of(open.saleId(), soldOut.saleId(), ended.saleId())) {
            jdbc.update("UPDATE sales SET starts_at = UTC_TIMESTAMP(6) - INTERVAL 1 HOUR WHERE id = ?", id);
        }
        jdbc.update("UPDATE sales SET reserved_quantity = total_quantity WHERE id = ?", open.saleId());
        jdbc.update("UPDATE sales SET sold_quantity = total_quantity WHERE id = ?", soldOut.saleId());
        jdbc.update("UPDATE sales SET ends_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND, is_stopped = true WHERE id = ?",
            ended.saleId());
        for (String status : List.of("SCHEDULED", "STOPPED", "OPEN", "SOLD_OUT", "ENDED")) {
            var result = queries.list(0, 20, product1, status);
            assertThat(result.totalElements()).as(status).isEqualTo(1);
            assertThat(result.items().get(0).status()).isEqualTo(status);
        }
        assertThat(queries.get(scheduled.saleId()).status()).isEqualTo("SCHEDULED");
        assertThat(queries.get(stopped.saleId()).status()).isEqualTo("STOPPED");
        Client anonymous = new Client();
        var page = data(anonymous.request("GET", "/api/v1/sales", null, null));
        assertThat(page.path("totalElements").asLong()).isEqualTo(5);
        assertThat(page.path("items").get(0).path("saleId").asLong()).isEqualTo(ended.saleId());
        assertThat(fields(page.path("items").get(0))).doesNotContain("description", "availableQuantity",
            "reservedQuantity", "soldQuantity");
        assertThat(queries.list(0, 20, product2, "OPEN").items()).isEmpty();
        assertThat(queries.list(Integer.MAX_VALUE, 100, null, null).items()).isEmpty();
        assertThat(queries.list(1, 2, null, null).totalPages()).isEqualTo(3);
        assertThat(commands.stop(open.saleId()).status()).isEqualTo("STOPPED");
        assertThat(commands.resume(soldOut.saleId()).status()).isEqualTo("SOLD_OUT");
        assertThatThrownBy(() -> commands.resume(ended.saleId())).isInstanceOf(SaleException.class);
        var changed = commands.patch(ended.saleId(), patch(ended.version(), "ended metadata", null));
        assertThat(changed.name()).isEqualTo("ended metadata");
        assertThatThrownBy(() -> commands.delete(ended.saleId())).isInstanceOf(SaleException.class);
    }

    @Test
    void strictInputsRejectInvalidFieldsAndDatesWithoutSaving() throws Exception {
        Client admin = new Client();
        admin.login("sale-admin@example.com");
        String valid = body(product1);
        String path = "/api/v1/admin/sales";
        for (String invalid : List.of("{}", "null", "[]", valid + " garbage", valid + " {}",
                valid.replace("}", ",\"totalQuantity\":10}"), valid.replace("}", ",\"unknown\":1}"),
                valid.replace("}", ",\"unitPrice\":1.5}"), valid.replace("}", ",\"perMemberLimit\":11}"),
                valid.replace("\"totalQuantity\":10", "\"totalQuantity\":0"),
                valid.replace("\"totalQuantity\":10", "\"totalQuantity\":1000001"),
                valid.replace("\"name\":\"  sale  \"", "\"name\":null"),
                valid.replace("}", ",\"unitPrice\":0}"), valid.replace("}", ",\"unitPrice\":1000000001}"))) {
            var result = admin.request("POST", path, invalid, admin.token);
            assertThat(result.statusCode()).as(invalid).isEqualTo(400);
            assertThat(JSON.readTree(result.body()).path("code").asText()).isEqualTo("INVALID_INPUT");
        }
        assertThat(queries.list(0, 20, null, null).totalElements()).isZero();
        var invalidTime = JSON.readTree(valid).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalidTime).put("startsAt", "2000-01-01T00:00:00Z");
        assertThat(admin.request("POST", path, invalidTime.toString(), admin.token).statusCode()).isEqualTo(400);
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalidTime).put("startsAt", "2030-01-01T00:00:00");
        assertThat(admin.request("POST", path, invalidTime.toString(), admin.token).statusCode()).isEqualTo(400);
        assertThat(admin.request("POST", path, body(Long.MAX_VALUE), admin.token).statusCode()).isEqualTo(404);
        SaleDetails sale = create();
        for (String bad : List.of("{\"name\":\"missing version\"}", "{\"version\":0,\"unitPrice\":null}",
                "{\"version\":0,\"name\":null}", "{\"version\":0,\"startsAt\":null}")) {
            assertThat(admin.request("PATCH", path + "/" + sale.saleId(), bad, admin.token).statusCode())
                .isEqualTo(400);
        }
        for (String query : List.of("?page=-1", "?page=2147483648", "?size=0", "?size=101", "?status=unknown",
                "?productId=0", "?productId=x")) {
            assertThat(admin.request("GET", "/api/v1/sales" + query, null, null).statusCode()).as(query).isEqualTo(400);
        }
        assertThat(admin.request("GET", "/api/v1/sales/0", null, null).statusCode()).isEqualTo(400);
        assertThat(admin.request("GET", "/api/v1/sales/" + Long.MAX_VALUE, null, null).statusCode()).isEqualTo(404);
        jdbc.update("UPDATE sales SET starts_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", sale.saleId());
        assertThat(admin.request("PATCH", path + "/" + sale.saleId(),
            "{\"version\":0,\"unitPrice\":1000}", admin.token).statusCode()).isEqualTo(409);
        assertThat(admin.request("PATCH", path + "/" + sale.saleId(),
            "{\"version\":0,\"name\":\"after start\"}", admin.token).statusCode()).isEqualTo(200);
    }

    private static SalePatch patch(long version, String name, Long price) {
        return new SalePatch(version, null, name, null, price, null, null, null, null);
    }

    @Test
    void concurrentEditorsWithOneVersionCannotOverwriteEachOther() throws Exception {
        SaleDetails sale = create();
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (String name : List.of("first", "second")) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(go.await(5, TimeUnit.SECONDS)).isTrue();
                    try {
                        commands.patch(sale.saleId(), patch(0, name, null));
                        return "success";
                    } catch (SaleException exception) {
                        return exception.kind().name();
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("success", "VERSION_CONFLICT");
        }
        assertThat(queries.get(sale.saleId()).version()).isEqualTo(1);
    }

    @Test
    void waitingForLockPastStartRejectsTermsChangeAndDeletion() throws Exception {
        for (boolean delete : List.of(false, true)) {
            SaleDetails sale = create();
            jdbc.update("UPDATE sales SET starts_at = UTC_TIMESTAMP(6) + INTERVAL 2 SECOND WHERE id = ?",
                sale.saleId());
            Instant startsAt = Instant.parse(jdbc.queryForObject(
                "SELECT DATE_FORMAT(starts_at, '%Y-%m-%dT%H:%i:%s.%fZ') FROM sales WHERE id = ?",
                String.class, sale.saleId()));
            try (Connection holder = context.getBean(DataSource.class).getConnection();
                    var executor = Executors.newSingleThreadExecutor()) {
                try {
                    holder.setAutoCommit(false);
                    try (var statement = holder.prepareStatement("SELECT id FROM sales WHERE id = ? FOR UPDATE")) {
                        statement.setLong(1, sale.saleId());
                        try (var rows = statement.executeQuery()) {
                            assertThat(rows.next()).isTrue();
                        }
                    }
                    var future = executor.submit(() -> {
                        try {
                            if (delete) {
                                commands.delete(sale.saleId());
                            } else {
                                commands.patch(sale.saleId(), patch(0, null, 3000L));
                            }
                            return "unexpected success";
                        } catch (SaleException exception) {
                            return exception.kind().name();
                        }
                    });
                    boolean waiting = false;
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                    while (System.nanoTime() < deadline) {
                        waiting = jdbc.queryForList("SHOW FULL PROCESSLIST").stream().anyMatch(row ->
                            row.get("Info") != null && row.get("Info").toString().toLowerCase(java.util.Locale.ROOT)
                                .contains("for update"));
                        if (waiting) {
                            break;
                        }
                        Thread.sleep(10);
                    }
                    assertThat(waiting).as("request actually waits on MySQL lock before start").isTrue();
                    assertThat(dbNow()).isBefore(startsAt);
                    while (dbNow().isBefore(startsAt.plusMillis(50))) {
                        Thread.sleep(10);
                    }
                    holder.commit();
                    assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("STARTED");
                } finally {
                    holder.rollback();
                }
            }
            assertThat(queries.get(sale.saleId()).unitPrice()).isEqualTo(1000);
            assertThat(queries.get(sale.saleId()).version()).isZero();
        }
    }
}
