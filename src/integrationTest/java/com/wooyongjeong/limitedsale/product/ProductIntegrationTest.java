package com.wooyongjeong.limitedsale.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wooyongjeong.limitedsale.LimitedSaleApplication;
import com.wooyongjeong.limitedsale.member.application.MemberService;
import com.wooyongjeong.limitedsale.product.api.ProductCommand;
import com.wooyongjeong.limitedsale.product.api.ProductQuery;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class ProductIntegrationTest {
    private static final MySQLContainer<?> DB = new MySQLContainer<>("mysql:8.4.7")
        .withDatabaseName("sales").withUsername("sales_user").withPassword("test_password");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static ConfigurableApplicationContext context;
    private static int port;

    @BeforeAll
    static void start() throws Exception {
        DB.start();
        REDIS.start();
        context = new SpringApplicationBuilder(LimitedSaleApplication.class).run("--server.port=0",
            "--spring.datasource.url=" + DB.getJdbcUrl(), "--spring.datasource.username=" + DB.getUsername(),
            "--spring.datasource.password=" + DB.getPassword(), "--spring.data.redis.host=" + REDIS.getHost(),
            "--spring.data.redis.port=" + REDIS.getMappedPort(6379), "--spring.data.redis.timeout=1s",
            "--spring.data.redis.connect-timeout=1s",
            "--spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration");
        port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        assertThat(context.getBean(ProductQuery.class).list(0, 20).items()).isEmpty();
        assertThat(context.getBean(ProductQuery.class).list(0, 20).totalPages()).isZero();
        var empty = new Client().request("GET", "/api/v1/products", null, null);
        assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(data(empty).path("items").isEmpty()).isTrue();
        assertThat(data(empty).path("totalPages").asInt()).isZero();
        context.getBean(MemberService.class).initializeAdmin("product-admin@example.com", "password", "prodadmin");
        context.getBean(MemberService.class).register("product-user@example.com", "password", "produser");
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
        final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String token;

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
            token = JSON.readTree(request("GET", "/api/v1/csrf", null, null).body())
                .path("data").path("token").asText();
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

    private static void invalidField(HttpResponse<String> response, String expectedField) throws Exception {
        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode errors = data(response);
        assertThat(errors.isArray()).isTrue();
        assertThat(errors).hasSize(1);
        assertThat(fields(errors.get(0))).containsExactlyInAnyOrder("field", "reason");
        assertThat(errors.get(0).path("field").asText()).isEqualTo(expectedField);
        assertThat(errors.get(0).path("reason").asText()).isNotBlank().doesNotContain("secret");
    }

    @Test
    void endToEndContractsAndBoundaries() throws Exception {
        Client anonymous = new Client();
        Client admin = new Client();
        Client user = new Client();
        admin.login("product-admin@example.com");
        user.login("product-user@example.com");
        long initialTotal = context.getBean(ProductQuery.class).list(0, 20).totalElements();
        String path = "/api/v1/admin/products";
        String valid = "{\"name\":\"  제품  이름  \",\"description\":\"  설명\\n \",\"basePrice\":1}";
        assertThat(admin.request("POST", path, valid, null).statusCode()).isEqualTo(403);
        assertThat(admin.request("POST", path, valid, "invalid").statusCode()).isEqualTo(403);
        assertThat(user.request("POST", path, valid, user.token).statusCode()).isEqualTo(403);
        anonymous.csrf();
        assertThat(anonymous.request("POST", path, valid, anonymous.token).statusCode()).isEqualTo(401);
        var response = admin.request("POST", path, valid, admin.token);
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode created = data(response);
        assertThat(fields(created)).containsExactlyInAnyOrder("productId", "name", "description", "basePrice",
            "createdAt", "updatedAt");
        assertThat(created.path("name").asText()).isEqualTo("제품  이름");
        assertThat(created.path("description").asText()).isEqualTo("  설명\n ");
        long id = created.path("productId").asLong();
        assertThat(Instant.parse(created.path("createdAt").asText())).isEqualTo(
            Instant.parse(created.path("updatedAt").asText()));
        assertThat(data(anonymous.request("GET", "/api/v1/products/" + id, null, null))).isEqualTo(created);
        assertThat(context.getBean(ProductQuery.class).find(id).orElseThrow().name()).isEqualTo("제품  이름");
        var duplicate = admin.request("POST", path, valid, admin.token);
        assertThat(duplicate.statusCode()).isEqualTo(201);
        assertThat(data(duplicate).path("productId").asLong()).isGreaterThan(id);
        var maxValues = admin.request("POST", path, "{\"name\":\"" + "x".repeat(100)
            + "\",\"description\":\"" + "y".repeat(2000) + "\",\"basePrice\":1000000000}", admin.token);
        assertThat(maxValues.statusCode()).isEqualTo(201);
        assertThat(data(maxValues).path("description").asText()).isEqualTo("y".repeat(2000));
        long maxId = data(maxValues).path("productId").asLong();
        assertThat(data(anonymous.request("GET", "/api/v1/products/" + maxId, null, null))
            .path("description").asText()).isEqualTo("y".repeat(2000));
        var emptyDescription = admin.request("POST", path,
            "{\"name\":\"empty description\",\"basePrice\":1}", admin.token);
        assertThat(emptyDescription.statusCode()).isEqualTo(201);
        assertThat(data(emptyDescription).path("description").asText()).isEmpty();
        for (String invalid : List.of("{}", "{\"name\":null,\"basePrice\":1}",
                "{\"name\":\"   \",\"basePrice\":1}", "{\"name\":\"" + "x".repeat(101) + "\",\"basePrice\":1}",
                "{\"name\":\"a\",\"description\":\"" + "x".repeat(2001) + "\",\"basePrice\":1}",
                "{\"name\":\"a\",\"basePrice\":0}", "{\"name\":\"a\",\"basePrice\":-1}",
                "{\"name\":\"a\",\"basePrice\":1000000001}", "{\"name\":\"a\",\"basePrice\":1.5}",
                "{\"name\":\"a\",\"basePrice\":\"1\"}", "{\"name\":\"a\",\"basePrice\":1,\"extra\":true}",
                "{\"name\":\"a\",\"basePrice\":null}", "{")) {
            var bad = admin.request("POST", path, invalid, admin.token);
            assertThat(bad.statusCode()).as(invalid).isEqualTo(400);
            assertThat(JSON.readTree(bad.body()).path("code").asText()).isEqualTo("INVALID_INPUT");
        }
        invalidField(admin.request("POST", path, "{\"name\":\"  \",\"basePrice\":1}", admin.token), "name");
        invalidField(admin.request("POST", path, "{\"name\":42,\"basePrice\":1}", admin.token), "name");
        invalidField(admin.request("POST", path, "{\"name\":\"a\",\"description\":42,\"basePrice\":1}",
            admin.token), "description");
        invalidField(admin.request("POST", path, "{\"name\":\"a\",\"description\":\""
            + "x".repeat(2001) + "\",\"basePrice\":1}", admin.token), "description");
        invalidField(admin.request("POST", path, "{\"name\":\"a\",\"basePrice\":1.5}", admin.token), "basePrice");
        invalidField(admin.request("POST", path, "{\"name\":\"a\",\"basePrice\":0}", admin.token), "basePrice");
        invalidField(admin.request("POST", path, "{\"name\":\"a\",\"basePrice\":1,\"secret\":true}", admin.token),
            "body");
        assertThat(data(admin.request("POST", path, "{", admin.token)).isNull()).isTrue();
        invalidField(anonymous.request("GET", "/api/v1/products?page=-1", null, null), "page");
        invalidField(anonymous.request("GET", "/api/v1/products?page=2147483648", null, null), "page");
        invalidField(anonymous.request("GET", "/api/v1/products?size=101", null, null), "size");
        invalidField(anonymous.request("GET", "/api/v1/products?size=abc", null, null), "size");
        invalidField(anonymous.request("GET", "/api/v1/products/0", null, null), "productId");
        invalidField(anonymous.request("GET", "/api/v1/products/invalid", null, null), "productId");
        JsonNode listing = data(anonymous.request("GET", "/api/v1/products", null, null));
        assertThat(fields(listing)).containsExactlyInAnyOrder("items", "page", "size", "totalElements", "totalPages");
        assertThat(listing.path("page").asInt()).isZero();
        assertThat(listing.path("size").asInt()).isEqualTo(20);
        assertThat(listing.path("totalElements").asLong()).isEqualTo(initialTotal + 4);
        assertThat(listing.path("totalPages").asInt()).isEqualTo(1);
        assertThat(fields(listing.path("items").get(0))).containsExactlyInAnyOrder("productId", "name", "basePrice",
            "createdAt", "updatedAt");
        assertThat(listing.path("items").get(0).path("productId").asLong())
            .isGreaterThan(listing.path("items").get(1).path("productId").asLong());
        assertThat(data(anonymous.request("GET", "/api/v1/products?size=1&page=1", null, null))
            .path("totalPages").asLong()).isEqualTo(initialTotal + 4);
        assertThat(data(anonymous.request("GET", "/api/v1/products?size=100&page=2147483647", null, null))
            .path("items").isEmpty()).isTrue();
        assertThat(data(anonymous.request("GET", "/api/v1/products?size=1&page=2147483647", null, null))
            .path("items").isEmpty()).isTrue();
        for (String suffix : List.of("?page=-1", "?size=0", "?size=101", "?page=abc", "?page=2147483648",
                "?size=999999999999999999999", "?page=")) {
            assertThat(anonymous.request("GET", "/api/v1/products" + suffix, null, null).statusCode())
                .as(suffix).isEqualTo(400);
        }
        for (String invalid : List.of("0", "-1", "abc", "9223372036854775808")) {
            assertThat(anonymous.request("GET", "/api/v1/products/" + invalid, null, null).statusCode())
                .as(invalid).isEqualTo(400);
        }
        assertThat(anonymous.request("GET", "/api/v1/products/9223372036854775807", null, null).statusCode())
            .isEqualTo(404);
    }

    @Test
    void registrationRequiresExactlyOneObjectWithoutDuplicateFields() throws Exception {
        Client admin = new Client();
        admin.login("product-admin@example.com");
        String path = "/api/v1/admin/products";
        String valid = "{\"name\":\"파서 검증\",\"basePrice\":1}";
        var softly = new org.assertj.core.api.SoftAssertions();
        for (String body : List.of(valid + " {}", valid + " 1", valid + " garbage",
                "{\"name\":\"parser test\",\"basePrice\":1,\"basePrice\":2}",
                "{\"name\":\"parser test\",\"name\":\"second\",\"basePrice\":1}")) {
            long before = context.getBean(ProductQuery.class).list(0, 20).totalElements();
            var response = admin.request("POST", path, body, admin.token);
            long after = context.getBean(ProductQuery.class).list(0, 20).totalElements();
            softly.assertThat(response.statusCode()).as(body + " status").isEqualTo(400);
            softly.assertThat(after).as(body + " row count").isEqualTo(before);
            if (response.statusCode() == 400) {
                softly.assertThat(JSON.readTree(response.body()).path("code").asText()).isEqualTo("INVALID_INPUT");
            }
        }
        long before = context.getBean(ProductQuery.class).list(0, 20).totalElements();
        var good = admin.request("POST", path, "  \n" + valid + " \t\r\n", admin.token);
        softly.assertThat(good.statusCode()).as("surrounding whitespace").isEqualTo(201);
        softly.assertThat(data(good).path("name").asText()).isEqualTo("파서 검증");
        softly.assertThat(context.getBean(ProductQuery.class).list(0, 20).totalElements()).isEqualTo(before + 1);
        var empty = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json").header("X-CSRF-TOKEN", admin.token)
            .POST(HttpRequest.BodyPublishers.noBody()).build();
        var emptyResult = admin.http.send(empty, HttpResponse.BodyHandlers.ofString());
        softly.assertThat(emptyResult.statusCode()).as("empty body").isEqualTo(400);
        softly.assertThat(JSON.readTree(emptyResult.body()).path("code").asText()).isEqualTo("INVALID_INPUT");
        var unsupported = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "text/plain").header("X-CSRF-TOKEN", admin.token)
            .POST(HttpRequest.BodyPublishers.ofString(valid)).build();
        var unsupportedResult = admin.http.send(unsupported, HttpResponse.BodyHandlers.ofString());
        softly.assertThat(unsupportedResult.statusCode()).as("unsupported content type").isEqualTo(415);
        softly.assertThat(JSON.readTree(unsupportedResult.body()).path("code").asText())
            .isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        softly.assertThat(context.getBean(ProductQuery.class).list(0, 20).totalElements()).isEqualTo(before + 1);
        softly.assertAll();
    }

    @Test
    void publicReadsSurviveRedisOutageAndProtectedCreationDoesNot() throws Exception {
        long id = context.getBean(ProductCommand.class).register("outage", null, 1L).productId();
        Client loggedIn = new Client();
        loggedIn.login("product-admin@example.com");
        Client anonymous = new Client();
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            assertThat(anonymous.request("GET", "/api/v1/products", null, null).statusCode()).isEqualTo(200);
            assertThat(loggedIn.request("GET", "/api/v1/products/" + id, null, null).statusCode()).isEqualTo(200);
            assertThat(anonymous.request("GET", "/api/v1/products/" + id, null, null).statusCode()).isEqualTo(200);
            var staleRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/products/" + id))
                .header("Cookie", "SESSION=c3RhbGUtc2Vzc2lvbg==").GET().build();
            assertThat(HttpClient.newHttpClient().send(staleRequest, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(200);
            assertThat(loggedIn.request("POST", "/api/v1/admin/products", "{}", loggedIn.token).statusCode())
                .isEqualTo(503);
        } finally {
            docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }
}
