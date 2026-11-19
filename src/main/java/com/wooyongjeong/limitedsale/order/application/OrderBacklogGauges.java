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

    public OrderBacklogGauges(OrderBacklog backlog, MeterRegistry registry) {
        this.backlog = backlog;
        Gauge.builder("order.unfinished", this, gauges -> gauges.unfinished)
            .description("Orders awaiting a payment outcome; their stock stays reserved").register(registry);
        Gauge.builder("order.requires.review", this, gauges -> gauges.requiringReview)
            .description("Unfinished orders flagged for operator review").register(registry);
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
    }
}
