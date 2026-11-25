package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.OrderBacklog;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Publishes the open-order counts from the database on a fixed delay instead of querying during each scrape.
 * A value is NaN until the first successful count and after a failed one, so a stale number is never shown.
 */
@Configuration
@EnableScheduling
public class OrderBacklogGauges {
    private static final Logger log = LoggerFactory.getLogger(OrderBacklogGauges.class);
    private final OrderBacklog backlog;
    private volatile double unfinished = Double.NaN;
    private volatile double requiringReview = Double.NaN;
    private volatile double unpublished = Double.NaN;
    private volatile double oldestUnpublished = Double.NaN;
    private volatile double accepted = Double.NaN;
    private volatile double oldestAccepted = Double.NaN;

    public OrderBacklogGauges(OrderBacklog backlog, MeterRegistry registry) {
        this.backlog = backlog;
        Gauge.builder("order.unfinished", this, gauges -> gauges.unfinished)
            .description("Orders awaiting a payment outcome; their stock stays reserved").register(registry);
        Gauge.builder("order.requires.review", this, gauges -> gauges.requiringReview)
            .description("Unfinished orders flagged for operator review").register(registry);
        Gauge.builder("order.outbox.unpublished", this, gauges -> gauges.unpublished)
            .description("Outbox rows not yet published to Kafka").register(registry);
        Gauge.builder("order.outbox.oldest.unpublished", this, gauges -> gauges.oldestUnpublished)
            .description("Age of the oldest unpublished outbox row; 0 when there is none").baseUnit("seconds")
            .register(registry);
        Gauge.builder("order.acceptance.accepted", this, gauges -> gauges.accepted)
            .description("v2 acceptances not yet finished (still ACCEPTED)").register(registry);
        Gauge.builder("order.acceptance.oldest.accepted", this, gauges -> gauges.oldestAccepted)
            .description("Age of the oldest unfinished v2 acceptance; 0 when there is none").baseUnit("seconds")
            .register(registry);
    }

    @Scheduled(fixedDelayString = "${limited-sale.observability.backlog-interval:PT10S}")
    public void refresh() {
        try {
            unfinished = backlog.unfinished();
            requiringReview = backlog.requiringReview();
        } catch (RuntimeException failure) {
            unfinished = Double.NaN;
            requiringReview = Double.NaN;
            log.warn("Order backlog gauges not refreshed: {}", failure.toString());
        }
        // Counted apart, so a failure here leaves the order counts above as they are.
        try {
            var outbox = backlog.unpublishedOutbox();
            var acceptances = backlog.acceptedRequests();
            unpublished = outbox.size();
            oldestUnpublished = outbox.oldest().toNanos() / 1e9;
            accepted = acceptances.size();
            oldestAccepted = acceptances.oldest().toNanos() / 1e9;
        } catch (RuntimeException failure) {
            unpublished = Double.NaN;
            oldestUnpublished = Double.NaN;
            accepted = Double.NaN;
            oldestAccepted = Double.NaN;
            log.warn("Asynchronous order backlog gauges not refreshed: {}", failure.toString());
        }
    }
}
