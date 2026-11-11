package com.wooyongjeong.limitedsale.member;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wooyongjeong.limitedsale.LimitedSaleApplication;
import com.wooyongjeong.limitedsale.member.application.MemberService;
import com.wooyongjeong.limitedsale.product.api.ProductCommand;
import com.wooyongjeong.limitedsale.member.domain.LoginAttemptLimit;
import com.wooyongjeong.limitedsale.member.infrastructure.security.RedisLoginAttemptLimit;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.HttpSessionIdResolver;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionIntegrationTest {
    private static final MySQLContainer<?> DB = new MySQLContainer<>("mysql:8.4.7")
        .withDatabaseName("sales").withUsername("sales_user").withPassword("test_password");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static ConfigurableApplicationContext first;
    private static ConfigurableApplicationContext second;
    private static int port1;
    private static int port2;

    @BeforeAll
    static void start() {
        DB.start();
        REDIS.start();
        String[] properties = {"server.port=0", "spring.datasource.url=" + DB.getJdbcUrl(),
            "spring.datasource.username=" + DB.getUsername(), "spring.datasource.password=" + DB.getPassword(),
            "spring.data.redis.host=" + REDIS.getHost(), "spring.data.redis.port=" + REDIS.getMappedPort(6379),
            "spring.data.redis.timeout=2s", "spring.data.redis.connect-timeout=2s",
            "spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration"};
        String[] args = java.util.Arrays.stream(properties).map(value -> "--" + value).toArray(String[]::new);
        first = new SpringApplicationBuilder(LimitedSaleApplication.class, TestEndpoints.class).run(args);
        second = new SpringApplicationBuilder(LimitedSaleApplication.class, TestEndpoints.class).run(args);
        port1 = ((ServletWebServerApplicationContext) first).getWebServer().getPort();
        port2 = ((ServletWebServerApplicationContext) second).getWebServer().getPort();
        first.getBean(MemberService.class).register("session@example.com", "password", "session");
        first.getBean(MemberService.class).register("limited@example.com", "password", "limited");
        first.getBean(MemberService.class).initializeAdmin("admin@example.com", "password", "admin");
        first.getBean(ProductCommand.class).register("session test product", "", 1L);
    }

    @RestController
    static class TestEndpoints {
        @GetMapping({"/api/v1/admin/test", "/api/v1/sales", "/api/v1/sales/1"})
        String endpoint() {
            return "ok";
        }
    }

    @AfterAll
    static void stop() {
        if (second != null) {
            second.close();
        }
        if (first != null) {
            first.close();
        }
        REDIS.stop();
        DB.stop();
    }

    private static class Client {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
        String token;
        String email = " SESSION@example.com ";

        HttpResponse<String> request(int port, String method, String path, String body, boolean csrf) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (csrf) {
                builder.header("X-CSRF-TOKEN", token);
            }
            builder.header("X-Forwarded-For", "192.0.2." + (port == port1 ? "1" : "2"));
            if (body != null) {
                builder.header("Content-Type", "application/json");
            }
            builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        void csrf(int port) throws Exception {
            var response = request(port, "GET", "/api/v1/csrf", null, false);
            assertThat(response.statusCode()).isEqualTo(200);
            token = JSON.readTree(response.body()).path("data").path("token").asText();
            assertThat(JSON.readTree(response.body()).path("data").path("headerName").asText())
                .isEqualTo("X-CSRF-TOKEN");
        }

        HttpResponse<String> login(int port, String password) throws Exception {
            return request(port, "POST", "/api/v1/sessions",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}", true);
        }
    }

    @Test
    void malformedMemberJsonHasNoSideEffects() throws Exception {
        Client client = new Client();
        client.csrf(port1);
        String registration = "{\"email\":\"strict@example.com\",\"password\":\"password\",\"nickname\":\"strictnick\"}";
        for (String body : List.of(registration + " garbage", registration + " {}", registration + " 1",
                registration.replace("}", ",\"role\":\"ADMIN\"}"),
                registration.replace("}", ",\"email\":\"other@example.com\"}"),
                registration.replace("\"strictnick\"", "123"),
                registration.replace("\"password\":\"password\"", "\"password\":false"),
                registration.replace("\"email\":\"strict@example.com\"", "\"email\":[]"), "[]", "null")) {
            var response = client.request(port1, "POST", "/api/v1/members", body, true);
            assertThat(response.statusCode()).as(body).isEqualTo(400);
            assertThat(JSON.readTree(response.body()).path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(JSON.readTree(response.body()).path("data").isNull()).isTrue();
        }
        assertThat(client.request(port1, "POST", "/api/v1/members", registration, true).statusCode()).isEqualTo(201);
        assertThat(client.request(port1, "POST", "/api/v1/members",
            "{\"email\":\"nonick@example.com\",\"password\":\"password\"}", true).statusCode()).isEqualTo(201);
        assertThat(client.request(port1, "POST", "/api/v1/members",
            "{\"email\":\"nullnick@example.com\",\"password\":\"password\",\"nickname\":null}", true)
            .statusCode()).isEqualTo(201);
        client.email = "strict@example.com";
        for (int i = 0; i < 4; i++) {
            assertThat(client.login(port1, "wrongpass").statusCode()).isEqualTo(401);
        }
        String login = "{\"email\":\"strict@example.com\",\"password\":\"password\"}";
        for (String body : List.of(login + " garbage", login + " {}", login + " true",
                login.replace("}", ",\"role\":\"ADMIN\"}"),
                login.replace("}", ",\"password\":\"password\"}"),
                login.replace("\"password\":\"password\"", "\"password\":true"),
                login.replace("\"email\":\"strict@example.com\"", "\"email\":{}"), "[]", "null")) {
            var response = client.request(port1, "POST", "/api/v1/sessions", body, true);
            assertThat(response.statusCode()).as(body).isEqualTo(400);
            assertThat(JSON.readTree(response.body()).path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(JSON.readTree(response.body()).path("data").isNull()).isTrue();
            assertThat(client.request(port1, "GET", "/api/v1/members/me", null, false).statusCode()).isEqualTo(401);
        }
        assertThat(client.login(port1, "password").statusCode()).isEqualTo(200);
    }

    @Test
    void unacceptableLogoutMediaDoesNotInvalidateSession() throws Exception {
        Client client = new Client();
        client.csrf(port1);
        assertThat(client.login(port1, "password").statusCode()).isEqualTo(200);
        client.csrf(port1);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port1 + "/api/v1/sessions/current"))
            .header("Accept", "application/xml").header("X-CSRF-TOKEN", client.token).DELETE().build();
        var response = client.http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(406);
        assertThat(client.request(port2, "GET", "/api/v1/members/me", null, false).statusCode()).isEqualTo(200);
    }

    @Test
    void unacceptableMediaDoesNotChangeLoginOrLogoutState() throws Exception {
        Client client = new Client();
        client.csrf(port1);
        String cookieBefore = client.cookies.getCookieStore().getCookies().toString();
        String body = "{\"email\":\"session@example.com\",\"password\":\"password\"}";
        var login = HttpRequest.newBuilder(URI.create("http://localhost:" + port1 + "/api/v1/sessions"))
            .header("Content-Type", "application/json").header("Accept", "application/xml")
            .header("X-CSRF-TOKEN", client.token).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = client.http.send(login, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(406);
        assertThat(client.cookies.getCookieStore().getCookies().toString()).isEqualTo(cookieBefore);
        assertThat(client.request(port1, "GET", "/api/v1/members/me", null, false).statusCode()).isEqualTo(401);
        assertThat(client.login(port1, "password").statusCode()).isEqualTo(200);
        client.csrf(port1);
        String loggedInCookie = client.cookies.getCookieStore().getCookies().toString();
        var logout = HttpRequest.newBuilder(URI.create("http://localhost:" + port1 + "/api/v1/sessions/current"))
            .header("Accept", "application/xml").header("X-CSRF-TOKEN", client.token).DELETE().build();
        response = client.http.send(logout, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(406);
        assertThat(client.cookies.getCookieStore().getCookies().toString()).isEqualTo(loggedInCookie);
        assertThat(client.request(port2, "GET", "/api/v1/members/me", null, false).statusCode()).isEqualTo(200);
        assertThat(client.request(port1, "DELETE", "/api/v1/sessions/current", null, true).statusCode()).isEqualTo(200);

        Client limited = new Client();
        limited.email = "xml-limit@example.com";
        first.getBean(MemberService.class).register(limited.email, "password", "xmlcount");
        limited.csrf(port1);
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(limited.login(port1, "wrongpass").statusCode()).isEqualTo(401);
        }
        var limitedLogin = HttpRequest.newBuilder(URI.create("http://localhost:" + port1 + "/api/v1/sessions"))
            .header("Content-Type", "application/json").header("Accept", "application/xml")
            .header("X-CSRF-TOKEN", limited.token).POST(HttpRequest.BodyPublishers.ofString(
                "{\"email\":\"xml-limit@example.com\",\"password\":\"password\"}")).build();
        assertThat(limited.http.send(limitedLogin, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(406);
        assertThat(limited.login(port1, "wrongpass").statusCode()).isEqualTo(429);
    }

    @Test
    void sharedSessionCsrfFixationAndCurrentLogout() throws Exception {
        Client a = new Client();
        Client b = new Client();
        assertThat(a.request(port1, "GET", "/api/v1/members/me", null, false).statusCode()).isEqualTo(401);
        assertThat(a.request(port1, "POST", "/api/v1/sessions", "{}", false).statusCode()).isEqualTo(403);
        a.csrf(port1);
        String oldCookie = a.cookies.getCookieStore().getCookies().toString();
        String oldToken = a.token;
        assertThat(a.login(port1, "password").statusCode()).isEqualTo(200);
        assertThat(a.cookies.getCookieStore().getCookies().toString()).isNotEqualTo(oldCookie);
        var me = a.request(port2, "GET", "/api/v1/members/me", null, false);
        assertThat(me.statusCode()).isEqualTo(200);
        JsonNode data = JSON.readTree(me.body()).path("data");
        assertThat(data.path("email").asText()).isEqualTo("session@example.com");
        assertThat(data.has("id")).isFalse();
        assertThat(a.request(port2, "DELETE", "/api/v1/sessions/current", null, true).statusCode()).isEqualTo(403);
        b.csrf(port2);
        assertThat(b.login(port2, "password").statusCode()).isEqualTo(200);
        a.csrf(port2);
        assertThat(a.token).isNotEqualTo(oldToken);
        assertThat(a.request(port2, "DELETE", "/api/v1/sessions/current", null, true).statusCode()).isEqualTo(200);
        assertThat(a.request(port1, "GET", "/api/v1/members/me", null, false).statusCode()).isEqualTo(401);
        assertThat(b.request(port1, "GET", "/api/v1/members/me", null, false).statusCode()).isEqualTo(200);
        assertThat(a.request(port1, "DELETE", "/api/v1/sessions/current", null, true).statusCode()).isEqualTo(403);
        a.csrf(port1);
        assertThat(a.request(port1, "DELETE", "/api/v1/sessions/current", null, true).statusCode()).isEqualTo(200);
    }

    @Test
    void unknownAccountAndIncorrectPasswordHaveSameResponse() throws Exception {
        Client client = new Client();
        client.csrf(port1);
        client.email = "unknown@example.com";
        var unknown = client.login(port1, "wrongpass");
        client.email = "session@example.com";
        var incorrect = client.login(port2, "wrongpass");
        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(incorrect.statusCode()).isEqualTo(401);
        assertThat(JSON.readTree(unknown.body()).path("code")).isEqualTo(JSON.readTree(incorrect.body()).path("code"));
        assertThat(JSON.readTree(unknown.body()).path("message"))
            .isEqualTo(JSON.readTree(incorrect.body()).path("message"));
    }

    @Test
    void adminInitializationStartsWithoutWebServer() {
        try (var context = new SpringApplicationBuilder(LimitedSaleApplication.class)
                .web(org.springframework.boot.WebApplicationType.NONE).run(
                    "--spring.datasource.url=" + DB.getJdbcUrl(),
                    "--spring.datasource.username=" + DB.getUsername(),
                    "--spring.datasource.password=" + DB.getPassword(),
                    "--spring.data.redis.host=" + REDIS.getHost(),
                    "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                    "--spring.flyway.locations=filesystem:src/main/resources/db/migration,"
                        + "classpath:db/nickmigration")) {
            assertThat(context.getBean(MemberService.class).initializeAdmin("admin@example.com", "password", "admin")
                .role().name()).isEqualTo("ADMIN");
        }
    }

    @Test
    void administratorRoleIsPreserved() throws Exception {
        Client client = new Client();
        client.email = "admin@example.com";
        client.csrf(port1);
        var login = client.login(port1, "password");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(login.body()).path("data").path("role").asText()).isEqualTo("ADMIN");
        var me = client.request(port2, "GET", "/api/v1/members/me", null, false);
        assertThat(JSON.readTree(me.body()).path("data").path("role").asText()).isEqualTo("ADMIN");
    }

    @Test
    void adminAndPublicRoutesEnforceRoles() throws Exception {
        Client anonymous = new Client();
        assertThat(anonymous.request(port1, "GET", "/api/v1/admin/test", null, false).statusCode()).isEqualTo(401);
        for (String path : List.of("/api/v1/products", "/api/v1/products/1", "/api/v1/sales", "/api/v1/sales/1")) {
            assertThat(anonymous.request(port1, "GET", path, null, false).statusCode()).isEqualTo(200);
        }
        Client user = new Client();
        user.csrf(port1);
        assertThat(user.login(port1, "password").statusCode()).isEqualTo(200);
        var forbidden = user.request(port2, "GET", "/api/v1/admin/test", null, false);
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(JSON.readTree(forbidden.body()).path("code").asText()).isEqualTo("ACCESS_DENIED");
        Client admin = new Client();
        admin.email = "admin@example.com";
        admin.csrf(port1);
        assertThat(admin.login(port1, "password").statusCode()).isEqualTo(200);
        assertThat(admin.request(port2, "GET", "/api/v1/admin/test", null, false).statusCode()).isEqualTo(200);
    }

    @Test
    void sessionCookieHasTransportAppropriateFlags() throws Exception {
        Client client = new Client();
        var response = client.request(port1, "GET", "/api/v1/csrf", null, false);
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(cookie).contains("HttpOnly", "SameSite=Lax").doesNotContain("; Secure");

        SessionRepositoryFilter<?> filter = first.getBean(SessionRepositoryFilter.class);
        HttpSessionIdResolver resolver = (HttpSessionIdResolver) ReflectionTestUtils.getField(filter,
            "httpSessionIdResolver");
        MockHttpServletRequest secureRequest = new MockHttpServletRequest();
        secureRequest.setSecure(true);
        MockHttpServletResponse secureResponse = new MockHttpServletResponse();
        resolver.setSessionId(secureRequest, secureResponse, "secure-test-session");
        assertThat(secureResponse.getHeader("Set-Cookie")).contains("HttpOnly", "SameSite=Lax", "Secure");
    }

    @Test
    void unsupportedLoginContentTypeHasNoSideEffects() throws Exception {
        Client client = new Client();
        client.csrf(port1);
        String cookie = client.cookies.getCookieStore().getCookies().toString();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port1 + "/api/v1/sessions"))
            .header("Content-Type", "text/plain").header("X-CSRF-TOKEN", client.token)
            .POST(HttpRequest.BodyPublishers.ofString("invalid")).build();
        var response = client.http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(JSON.readTree(response.body()).path("code").asText()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(client.cookies.getCookieStore().getCookies().toString()).isEqualTo(cookie);
        assertThat(client.request(port1, "GET", "/api/v1/members/me", null, false).statusCode()).isEqualTo(401);
        assertThat(client.login(port1, "password").statusCode()).isEqualTo(200);
    }

    @Test
    void customLimitExpiresAndLoginRecovers() throws Exception {
        StringRedisTemplate redis = first.getBean(StringRedisTemplate.class);
        assertThatThrownBy(() -> new RedisLoginAttemptLimit(redis, Duration.ofSeconds(1), 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisLoginAttemptLimit(redis, Duration.ofSeconds(1), -1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisLoginAttemptLimit(redis, Duration.ZERO, 3))
            .isInstanceOf(IllegalArgumentException.class);
        new ApplicationContextRunner().withBean(StringRedisTemplate.class, () -> redis)
            .withBean(RedisLoginAttemptLimit.class)
            .withInitializer(context -> context.getBeanFactory()
                .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withPropertyValues("limited-sale.auth.failure-window=PT1S", "limited-sale.auth.failure-limit=0")
            .run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasStackTraceContaining("Login failure limit must be positive"));
        String[] args = {"--server.port=0", "--spring.datasource.url=" + DB.getJdbcUrl(),
            "--spring.datasource.username=" + DB.getUsername(), "--spring.datasource.password=" + DB.getPassword(),
            "--spring.data.redis.host=" + REDIS.getHost(), "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
            "--spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration",
            "--limited-sale.auth.failure-limit=3", "--limited-sale.auth.failure-window=3s"};
        try (var custom = new SpringApplicationBuilder(LimitedSaleApplication.class).run(args)) {
            int port = ((ServletWebServerApplicationContext) custom).getWebServer().getPort();
            Client client = new Client();
            client.email = "custom-limit@example.com";
            first.getBean(MemberService.class).register(client.email, "password", "customlimit");
            client.csrf(port);
            assertThat(client.login(port, "wrongpass").statusCode()).isEqualTo(401);
            assertThat(client.login(port, "wrongpass").statusCode()).isEqualTo(401);
            assertThat(client.login(port, "wrongpass").statusCode()).isEqualTo(429);
            assertThat(custom.getBean(LoginAttemptLimit.class).blocked(client.email, "127.0.0.1")).isTrue();
            assertThat(client.login(port, "password").statusCode()).isEqualTo(429);
            Thread.sleep(3300);
            assertThat(client.login(port, "password").statusCode()).isEqualTo(200);
        }
    }

    @Test
    void fifthFailureAndResetAcrossInstances() throws Exception {
        Client client = new Client();
        client.email = "limited@example.com";
        client.csrf(port1);
        for (int i = 0; i < 4; i++) {
            assertThat(client.login(i % 2 == 0 ? port1 : port2, "wrongpass").statusCode()).isEqualTo(401);
        }
        assertThat(client.login(port2, "wrongpass").statusCode()).isEqualTo(429);
        assertThat(client.login(port1, "password").statusCode()).isEqualTo(429);
    }

    @Test
    void fixedWindowAndSuccessfulReset() throws Exception {
        LoginAttemptLimit limit = first.getBean(LoginAttemptLimit.class);
        var redis = first.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
        String email = "window@example.com";
        assertThat(limit.finish(email, "local", false)).isFalse();
        var keys = redis.keys("limited-sale:login-failure:*");
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest((email.length() + ":" + email + "local").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String key = "limited-sale:login-failure:" + digest;
        assertThat(keys).contains(key);
        redis.expire(key, java.time.Duration.ofSeconds(10));
        long before = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(limit.finish(email, "local", false)).isFalse();
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(before);
        assertThat(limit.finish(email, "local", true)).isFalse();
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(limit.finish(email, "local", false)).isFalse();
        redis.expire(key, java.time.Duration.ofMillis(100));
        Thread.sleep(200);
        assertThat(limit.blocked(email, "local")).isFalse();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void idleSessionExpires() throws Exception {
        Client client = new Client();
        client.csrf(port1);
        assertThat(client.login(port1, "password").statusCode()).isEqualTo(200);
        String cookie = client.cookies.getCookieStore().getCookies().stream()
            .filter(value -> value.getName().equals("SESSION")).findFirst().orElseThrow().getValue();
        String id = new String(java.util.Base64.getDecoder().decode(cookie), java.nio.charset.StandardCharsets.UTF_8);
        var repository = first.getBean(org.springframework.session.SessionRepository.class);
        org.springframework.session.Session session = (org.springframework.session.Session) repository.findById(id);
        session.setMaxInactiveInterval(java.time.Duration.ofSeconds(3));
        repository.save(session);
        Thread.sleep(2000);
        assertThat(client.request(port2, "GET", "/api/v1/products", null, false).statusCode()).isEqualTo(200);
        Thread.sleep(2000);
        assertThat(client.request(port1, "GET", "/api/v1/members/me", null, false).statusCode()).isEqualTo(200);
        Thread.sleep(4000);
        assertThat(client.request(port2, "GET", "/api/v1/members/me", null, false).statusCode()).isEqualTo(401);
    }

    @Test
    void redisOutageRejectsSessionOperationsAndRecovers() throws Exception {
        Client client = new Client();
        client.csrf(port1);
        assertThat(client.login(port1, "password").statusCode()).isEqualTo(200);
        client.csrf(port1);
        Client stale = new Client();
        stale.csrf(port1);
        String staleValue = stale.cookies.getCookieStore().getCookies().stream()
            .filter(cookie -> cookie.getName().equals("SESSION")).findFirst().orElseThrow().getValue();
        assertThat(stale.request(port1, "DELETE", "/api/v1/sessions/current", null, true).statusCode())
            .isEqualTo(200);
        HttpCookie staleCookie = new HttpCookie("SESSION", staleValue);
        staleCookie.setPath("/");
        stale.cookies.getCookieStore().add(URI.create("http://localhost:" + port1), staleCookie);
        URI base = URI.create("http://localhost:" + port1 + "/api/v1/sales/1");
        assertThat(stale.cookies.get(base, java.util.Map.of()).toString()).contains(staleValue);
        String currentCookie = client.cookies.getCookieStore().getCookies().toString();
        var docker = org.testcontainers.DockerClientFactory.instance().client();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            Client anonymous = new Client();
            assertThat(anonymous.request(port1, "GET", "/api/v1/products", null, false).statusCode()).isEqualTo(200);
            assertThat(client.request(port1, "GET", "/api/v1/products/1", null, false).statusCode()).isEqualTo(200);
            assertThat(stale.request(port1, "GET", "/api/v1/sales/1", null, false).statusCode()).isEqualTo(200);
            assertThat(client.cookies.getCookieStore().getCookies().toString()).isEqualTo(currentCookie);
            assertThat(stale.cookies.get(base, java.util.Map.of()).toString()).contains(staleValue);
            var health = anonymous.request(port1, "GET", "/actuator/health", null, false);
            assertThat(health.statusCode()).isEqualTo(503);
            assertThat(health.body()).doesNotContain("AUTHENTICATION_REQUIRED", "AUTHENTICATION_UNAVAILABLE");
            assertThat(anonymous.request(port1, "GET", "/api/v1/csrf", null, false).statusCode()).isEqualTo(503);
            assertThat(client.request(port1, "GET", "/api/v1/members/me", null, false).statusCode()).isEqualTo(503);
            assertThat(client.request(port1, "GET", "/api/v1/csrf", null, false).statusCode()).isEqualTo(503);
            assertThat(client.request(port1, "DELETE", "/api/v1/sessions/current", null, true).statusCode())
                .isEqualTo(503);
            assertThat(client.login(port1, "password").statusCode()).isEqualTo(503);
        } finally {
            docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        assertThat(client.request(port2, "GET", "/api/v1/members/me", null, false).statusCode()).isEqualTo(200);
        assertThat(client.request(port2, "DELETE", "/api/v1/sessions/current", null, true).statusCode()).isEqualTo(200);
    }

    @Test
    void atomicConcurrentFinalization() throws Exception {
        LoginAttemptLimit a = first.getBean(LoginAttemptLimit.class);
        LoginAttemptLimit b = second.getBean(LoginAttemptLimit.class);
        String email = "parallel@example.com";
        try (var executor = Executors.newFixedThreadPool(12)) {
            CountDownLatch start = new CountDownLatch(1);
            List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                LoginAttemptLimit limit = i % 2 == 0 ? a : b;
                results.add(executor.submit(() -> {
                    start.await();
                    return limit.finish(email, "127.0.0.1", false);
                }));
            }
            start.countDown();
            int blocked = 0;
            for (var result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    blocked++;
                }
            }
            assertThat(blocked).isEqualTo(8);
        }
        assertThat(a.finish(email, "127.0.0.1", true)).isTrue();
        assertThat(a.blocked(email, "another-ip")).isFalse();
        for (int round = 0; round < 20; round++) {
            String racing = "race" + round + "@example.com";
            for (int i = 0; i < 4; i++) {
                assertThat(a.finish(racing, "local", false)).isFalse();
            }
            try (var executor = Executors.newFixedThreadPool(2)) {
                CountDownLatch start = new CountDownLatch(1);
                var success = executor.submit(() -> {
                    start.await();
                    return a.finish(racing, "local", true);
                });
                var failure = executor.submit(() -> {
                    start.await();
                    return b.finish(racing, "local", false);
                });
                start.countDown();
                boolean successBlocked = success.get(10, TimeUnit.SECONDS);
                boolean failureBlocked = failure.get(10, TimeUnit.SECONDS);
                assertThat(successBlocked).isEqualTo(failureBlocked);
                assertThat(a.blocked(racing, "local")).isEqualTo(successBlocked);
            }
        }
        assertThat(b.blocked(email, "127.0.0.1")).isTrue();
        assertThat(a.finish("reset@example.com", "127.0.0.1", false)).isFalse();
        assertThat(b.finish("reset@example.com", "127.0.0.1", true)).isFalse();
        assertThat(a.blocked("reset@example.com", "127.0.0.1")).isFalse();
    }
}
