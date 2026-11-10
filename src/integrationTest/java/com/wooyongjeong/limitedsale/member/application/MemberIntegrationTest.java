package com.wooyongjeong.limitedsale.member.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wooyongjeong.limitedsale.member.domain.Member;
import com.wooyongjeong.limitedsale.member.domain.MemberConflict;
import com.wooyongjeong.limitedsale.member.domain.MemberRole;
import com.wooyongjeong.limitedsale.member.domain.MemberStore;
import com.wooyongjeong.limitedsale.member.domain.PasswordHasher;
import com.wooyongjeong.limitedsale.member.infrastructure.persistence.JdbcMemberStore;
import com.wooyongjeong.limitedsale.member.presentation.MemberController;
import com.wooyongjeong.limitedsale.member.presentation.MemberExceptionHandler;
import jakarta.validation.Validator;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class MemberIntegrationTest {
    private static final MySQLContainer<?> DB = new MySQLContainer<>("mysql:8.4.7")
        .withDatabaseName("sales").withUsername("sales_user").withPassword("test_password");

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static {
        DB.start();
        REDIS.start();
    }

    @Autowired
    private MemberService service;
    @Autowired
    private MemberStore store;
    @Autowired
    private Validator validator;
    @Autowired
    private PasswordHasher passwordHasher;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.locations",
            () -> "filesystem:src/main/resources/db/migration,classpath:db/nickmigration");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @AfterAll
    static void stop() {
        DB.stop();
        REDIS.stop();
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM members");
    }

    @Test
    void registrationHttpAndHash() throws Exception {
        mvc.perform(post("/api/v1/members").with(csrf()).contentType("application/json")
                .content("{\"email\":\" TEST@example.com \",\"password\":\"password\","
                    + "\"nickname\":\"  가  Name  \",\"role\":\"ADMIN\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value("MEMBER_CREATED"))
            .andExpect(jsonPath("$.data.email").value("test@example.com"))
            .andExpect(jsonPath("$.data.nickname").value("가 Name"))
            .andExpect(jsonPath("$.data.role").value("USER"))
            .andExpect(jsonPath("$.data.id").doesNotExist())
            .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        String hash = jdbc.queryForObject("SELECT password_hash FROM members", String.class);
        assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16, 32, 1, 19456, 2)
            .matches("password", hash)).isTrue();
        mvc.perform(post("/api/v1/members").with(csrf()).contentType("application/json")
                .content("{\"email\":\"test@example.com\",\"password\":\"password\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
        mvc.perform(post("/api/v1/members").with(csrf()).contentType("application/json")
                .content("{\"email\":\"other@example.com\",\"password\":\"password\",\"nickname\":\"가 name\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NICKNAME_ALREADY_EXISTS"));
        mvc.perform(post("/api/v1/members").with(csrf()).contentType("application/json")
                .content("{\"email\":\"broken\",\"password\":\"short\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data[0].field").value("email"));
    }

    @Test
    void unsupportedMediaTypesDoNotCreateMembers() throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        URI uri = URI.create("http://localhost:" + port + "/api/v1/members");
        var tokenResponse = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/csrf"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
        String token = objectMapper.readTree(tokenResponse.body()).path("data").path("token").asText();
        HttpRequest invalidContent = HttpRequest.newBuilder(uri).header("X-CSRF-TOKEN", token)
            .header("Content-Type", "text/plain")
            .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString("hello")).build();
        HttpResponse<String> unsupported = client.send(invalidContent, HttpResponse.BodyHandlers.ofString());
        assertThat(unsupported.statusCode()).isEqualTo(415);
        assertThat(objectMapper.readTree(unsupported.body()).get("httpStatus").asInt()).isEqualTo(415);
        assertThat(objectMapper.readTree(unsupported.body()).get("code").asText()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");

        HttpRequest invalidAccept = HttpRequest.newBuilder(uri).header("X-CSRF-TOKEN", token)
            .header("Content-Type", "application/json")
            .header("Accept", "application/xml")
            .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"test@example.com\",\"password\":\"password\"}"))
            .build();
        HttpResponse<String> unacceptable = client.send(invalidAccept, HttpResponse.BodyHandlers.ofString());
        assertThat(unacceptable.statusCode()).isEqualTo(406);
        assertThat(unacceptable.body()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM members", Integer.class)).isZero();
    }

    @Test
    void emailLengthBoundaryIsEnforcedAfterNormalization() {
        String local = "a".repeat(64);
        String domain = "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);
        String email = local + "@" + domain;
        assertThat(email).hasSize(254);
        assertThat(service.register("  " + email.toUpperCase() + "  ", "password", "이메일경계").email())
            .isEqualTo(email);
        assertThatThrownBy(() -> service.register(email + "d", "password", "다른이름"))
            .isInstanceOf(MemberService.InputException.class);
    }

    @Test
    void simultaneousEmailAndNicknameAreSettledByDatabase() throws Exception {
        assertConcurrent("same@example.com", "SAME@example.com", "One", "Two", MemberConflict.Kind.EMAIL);
        jdbc.update("DELETE FROM members");
        assertConcurrent("one@example.com", "two@example.com", "Name", "name", MemberConflict.Kind.NICKNAME);
    }

    private void assertConcurrent(String email1, String email2, String nickname1, String nickname2,
            MemberConflict.Kind kind) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CyclicBarrier beforeInsert = new CyclicBarrier(2);
        MemberStore synchronizedStore = new MemberStore() {
            @Override
            public Optional<Member> findByEmail(String email) {
                return store.findByEmail(email);
            }
            @Override
            public long firstAvailableNicknameSuffix(String key) {
                return store.firstAvailableNicknameSuffix(key);
            }
            @Override
            public void insert(String email, String hash, String nickname, MemberRole role) {
                try {
                    beforeInsert.await(30, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                store.insert(email, hash, nickname, role);
            }
        };
        MemberService concurrentService = new MemberService(synchronizedStore, passwordHasher, validator,
            () -> "발랄한 강아지");
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Object> first = pool.submit(() -> register(concurrentService, start, email1, nickname1));
            Future<Object> second = pool.submit(() -> register(concurrentService, start, email2, nickname2));
            start.countDown();
            List<Object> results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(results.stream().filter(Member.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(MemberConflict.class::isInstance)
                .map(MemberConflict.class::cast).map(MemberConflict::kind)).containsExactly(kind);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM members", Integer.class)).isEqualTo(1);
        }
    }

    private Object register(MemberService concurrentService, CountDownLatch start, String email, String nickname)
            throws Exception {
        start.await();
        try {
            return concurrentService.register(email, "password", nickname);
        } catch (MemberConflict conflict) {
            return conflict;
        }
    }

    @Test
    void automaticNamesChooseFirstGapAndHandleLength() {
        MemberService named = new MemberService(store, passwordHasher, validator, () -> "발랄한 강아지");
        named.register("a@example.com", "password", null);
        named.register("b@example.com", "password", null);
        named.register("c@example.com", "password", null);
        jdbc.update("DELETE FROM members WHERE nickname = ?", "발랄한 강아지1");
        assertThat(named.register("d@example.com", "password", null).nickname()).isEqualTo("발랄한 강아지1");
        String full = "가나다라마바사아자차카타";
        service.register("e@example.com", "password", full);
        AtomicInteger calls = new AtomicInteger();
        MemberService boundary = new MemberService(store, passwordHasher, validator,
            () -> calls.getAndIncrement() == 0 ? full : "새로운 이름");
        assertThat(boundary.register("f@example.com", "password", null).nickname()).isEqualTo("새로운 이름");
        assertThat(calls).hasValue(2);
    }

    @Test
    void manyExistingNamesNeedOneGapQueryAndPreserveCase() {
        List<String> names = new ArrayList<>();
        names.add("Base");
        IntStream.rangeClosed(1, 1000).filter(number -> number != 713)
            .mapToObj(number -> "base" + number).forEach(names::add);
        names.add("base0713");
        names.add("base0");
        names.add("base99abc");
        names.add("absent3");
        jdbc.batchUpdate("INSERT INTO members (email, password_hash, nickname, nickname_key, role, created_at, "
                + "updated_at) VALUES (?, 'hash', ?, LOWER(?), 'USER', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
            names, 200, (statement, name) -> {
                statement.setString(1, name + "@example.com");
                statement.setString(2, name);
                statement.setString(3, name);
            });
        AtomicInteger gapQueries = new AtomicInteger();
        JdbcTemplate countedJdbc = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                gapQueries.incrementAndGet();
                return super.queryForObject(sql, requiredType, args);
            }
        };
        MemberStore countedStore = new JdbcMemberStore(countedJdbc, transactionManager);
        MemberService named = new MemberService(countedStore, passwordHasher, validator, () -> "BaSe");
        assertThat(named.register("new@example.com", "password", null).nickname()).isEqualTo("BaSe713");
        assertThat(gapQueries).hasValue(1);
        assertThat(countedStore.firstAvailableNicknameSuffix("absent")).isZero();
        assertThat(gapQueries).hasValue(2);
    }

    @Test
    void automaticCollisionRetriesInANewTransaction() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger gapQueries = new AtomicInteger();
        AtomicInteger hashes = new AtomicInteger();
        MemberStore racing = new MemberStore() {
            @Override
            public Optional<Member> findByEmail(String email) {
                return store.findByEmail(email);
            }
            @Override
            public long firstAvailableNicknameSuffix(String key) {
                gapQueries.incrementAndGet();
                return store.firstAvailableNicknameSuffix(key);
            }
            @Override
            public void insert(String email, String hash, String nickname, MemberRole role) {
                if (attempts.getAndIncrement() == 0) {
                    store.insert("race@example.com", hash, nickname, MemberRole.USER);
                }
                store.insert(email, hash, nickname, role);
            }
        };
        PasswordHasher checkedHasher = password -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            hashes.incrementAndGet();
            return passwordHasher.hash(password);
        };
        MemberService racingService = new MemberService(racing, checkedHasher, validator, () -> "발랄한 강아지");
        assertThat(racingService.register("next@example.com", "password", null).nickname())
            .isEqualTo("발랄한 강아지1");
        assertThat(attempts).hasValue(2);
        assertThat(gapQueries).hasValue(2);
        assertThat(hashes).hasValue(1);
    }

    @Test
    void twentyDatabaseCollisionsLeaveNoPartialRegistration() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger gapQueries = new AtomicInteger();
        AtomicInteger hashes = new AtomicInteger();
        MemberStore racing = new MemberStore() {
            @Override
            public Optional<Member> findByEmail(String email) {
                return store.findByEmail(email);
            }
            @Override
            public long firstAvailableNicknameSuffix(String key) {
                gapQueries.incrementAndGet();
                return store.firstAvailableNicknameSuffix(key);
            }
            @Override
            public void insert(String email, String hash, String nickname, MemberRole role) {
                int number = attempts.incrementAndGet();
                store.insert("racer" + number + "@example.com", hash, nickname, MemberRole.USER);
                store.insert(email, hash, nickname, role);
            }
        };
        PasswordHasher checkedHasher = password -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            hashes.incrementAndGet();
            return passwordHasher.hash(password);
        };
        MemberService racingService = new MemberService(racing, checkedHasher, validator, () -> "발랄한 강아지");
        assertThatThrownBy(() -> racingService.register("target@example.com", "password", null))
            .isInstanceOf(MemberService.NicknameUnavailable.class);
        assertThat(attempts).hasValue(20);
        assertThat(gapQueries).hasValue(20);
        assertThat(hashes).hasValue(1);
        assertThat(store.findByEmail("target@example.com")).isEmpty();
    }

    @Test
    void exhaustedGenerationDoesNotCreateMember() throws Exception {
        String full = "가나다라마바사아자차카타";
        service.register("seed@example.com", "password", full);
        AtomicInteger generated = new AtomicInteger();
        MemberService exhausted = new MemberService(store, passwordHasher, validator, () -> {
            generated.incrementAndGet();
            return full;
        });
        assertThatThrownBy(() -> exhausted.register("no@example.com", "password", null))
            .isInstanceOf(MemberService.NicknameUnavailable.class);
        assertThat(store.findByEmail("no@example.com")).isEmpty();
        assertThat(generated).hasValue(20);
        MockMvc standalone = MockMvcBuilders.standaloneSetup(new MemberController(exhausted))
            .setControllerAdvice(new MemberExceptionHandler()).build();
        standalone.perform(post("/api/v1/members").contentType("application/json")
                .content("{\"email\":\"no@example.com\",\"password\":\"password\"}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("NICKNAME_GENERATION_UNAVAILABLE"));
        assertThat(store.findByEmail("no@example.com")).isEmpty();
    }

    @Test
    void administratorNeverChangesExistingAdminOrPromotesUser() {
        Member admin = service.initializeAdmin("admin@example.com", "password", "관리자");
        String hash = jdbc.queryForObject("SELECT password_hash FROM members WHERE email = ?", String.class,
            admin.email());
        assertThat(service.initializeAdmin("admin@example.com", "newpassword", "다른이름")).isEqualTo(admin);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM members WHERE email = ?", String.class,
            admin.email())).isEqualTo(hash);
        service.register("user@example.com", "password", "사용자");
        assertThatThrownBy(() -> service.initializeAdmin("user@example.com", "password", "관리자2"))
            .isInstanceOf(MemberService.ExistingUser.class);
        assertThat(store.findByEmail("user@example.com").orElseThrow().role()).isEqualTo(MemberRole.USER);
    }
}
