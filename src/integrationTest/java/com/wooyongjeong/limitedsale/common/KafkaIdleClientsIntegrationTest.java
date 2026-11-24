package com.wooyongjeong.limitedsale.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wooyongjeong.limitedsale.LimitedSaleApplication;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A context that does not use Kafka must not create a Kafka client. Contexts of the other integration tests are
 * like this one: another application.yml shadows the application's own on the test classpath, so none of its Kafka
 * settings apply and Spring Boot's defaults (broker localhost:9092, topics created at startup) are all there is.
 * Before the fix such a context left an admin client retrying against the missing broker, which wrote about
 * 100,000 log lines per context and made the integration suite three times slower.
 */
class KafkaIdleClientsIntegrationTest {
    @Test
    void contextWithKafkaProvisioningOffCreatesNoClientAndWritesNoKafkaLogs() throws Exception {
        var logs = new ListAppender<ILoggingEvent>();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logs.start();
        long begin = System.nanoTime();
        // Attached once the logging system is configured but before the context refreshes, where the admin client
        // used to be created and to start logging.
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(LimitedSaleApplication.class)
                .listeners((ApplicationListener<ApplicationPreparedEvent>) event -> root.addAppender(logs))
                .run("--server.port=0", "--spring.profiles.active=test")) {
            Duration startup = Duration.ofNanos(System.nanoTime() - begin);
            try {
                Thread.sleep(15_000);
            } finally {
                root.detachAppender(logs);
            }
            // The admin client's 60 s default timeout used to be spent inside startup.
            assertThat(startup).isLessThan(Duration.ofSeconds(30));

            List<String> clientThreads = Thread.getAllStackTraces().keySet().stream().map(Thread::getName)
                    .filter(name -> name.contains("kafka-admin-client-thread")
                            || name.contains("kafka-producer-network-thread")
                            || name.contains("kafka-coordinator-heartbeat-thread"))
                    .toList();
            assertThat(clientThreads).as("Kafka client threads").isEmpty();
            assertThat(logs.list.stream().filter(event -> event.getLoggerName().startsWith("org.apache.kafka")
                    || event.getFormattedMessage().contains("9092")).count()).as("Kafka client log lines").isZero();
        }
    }
}
