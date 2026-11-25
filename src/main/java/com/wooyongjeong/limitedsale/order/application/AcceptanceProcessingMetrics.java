package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.application.OrderAcceptanceProcessor.Outcome;
import com.wooyongjeong.limitedsale.order.domain.ProcessingFailure;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
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
    private final Map<Outcome, Timer> completions = new EnumMap<>(Outcome.class);
    private final Timer failedCompletion;
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
        for (Outcome outcome : List.of(Outcome.ORDER_CREATED, Outcome.REJECTED)) {
            completions.put(outcome, completionTimer(outcome.name().toLowerCase(Locale.ROOT), registry));
        }
        failedCompletion = completionTimer("processing_failed", registry);
        failedAttempts = Counter.builder("order.acceptance.retries")
            .description("Failed processing attempts of acceptance messages; each is retried or ends as a failure")
            .register(registry);
        republished = Counter.builder("order.acceptance.republished")
            .description("Stalled acceptances sent again as a new outbox row").register(registry);
    }

    private static Timer completionTimer(String result, MeterRegistry registry) {
        return Timer.builder("order.acceptance.completion")
            .description("Time from acceptance to its final state (database times), recorded after the commit")
            .tag("result", result).publishPercentileHistogram().maximumExpectedValue(Duration.ofMinutes(10))
            .register(registry);
    }

    /** A committed ORDER_CREATED or REJECTED, {@code sinceAcceptance} after the request was accepted. */
    public void completed(Outcome outcome, Duration sinceAcceptance) {
        Timer timer = completions.get(outcome);
        if (timer != null && sinceAcceptance != null) {
            timer.record(sinceAcceptance.isNegative() ? Duration.ZERO : sinceAcceptance);
        }
    }

    /** A committed PROCESSING_FAILED, whatever the reason. */
    public void failed(ProcessingFailure reason, Duration sinceAcceptance) {
        failures.get(reason).increment();
        failedCompletion.record(sinceAcceptance.isNegative() ? Duration.ZERO : sinceAcceptance);
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
