package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.application.OrderAcceptanceProcessor.Outcome;
import com.wooyongjeong.limitedsale.order.domain.ProcessingFailure;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Counts consumed acceptance messages by result. A message that cannot be read counts as skipped, the same as one that
 * names no acceptance: neither can ever be processed, and both are let through so they do not hold up the partition.
 */
@Component
public class AcceptanceProcessingMetrics {
    private final Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);
    private final Map<ProcessingFailure, Counter> failures = new EnumMap<>(ProcessingFailure.class);
    private final Counter failedAttempts;
    private final Counter republished;

    public AcceptanceProcessingMetrics(MeterRegistry registry) {
        // Registered up front so rate() and increase() see a zero baseline.
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, Counter.builder("order.acceptance.processed")
                .description("Consumed order acceptance messages by result")
                .tag("result", outcome.name().toLowerCase(Locale.ROOT)).register(registry));
        }
        for (ProcessingFailure reason : ProcessingFailure.values()) {
            failures.put(reason, Counter.builder("order.acceptance.failed")
                .description("Acceptances ended as PROCESSING_FAILED, by reason").tag("reason", reason.name())
                .register(registry));
        }
        failedAttempts = Counter.builder("order.acceptance.retries")
            .description("Failed processing attempts of acceptance messages; each is retried or ends as a failure")
            .register(registry);
        republished = Counter.builder("order.acceptance.republished")
            .description("Stalled acceptances sent again as a new outbox row").register(registry);
    }

    public void failed(ProcessingFailure reason) {
        failures.get(reason).increment();
    }

    public void attemptFailed() {
        failedAttempts.increment();
    }

    public void republished() {
        republished.increment();
    }

    public void record(Outcome outcome) {
        counters.get(outcome).increment();
    }

    public void unreadable() {
        record(Outcome.SKIPPED);
    }
}
