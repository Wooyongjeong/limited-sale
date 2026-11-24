package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.application.OrderAcceptanceProcessor.Outcome;
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

    public AcceptanceProcessingMetrics(MeterRegistry registry) {
        // Registered up front so rate() and increase() see a zero baseline.
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, Counter.builder("order.acceptance.processed")
                .description("Consumed order acceptance messages by result")
                .tag("result", outcome.name().toLowerCase(Locale.ROOT)).register(registry));
        }
    }

    public void record(Outcome outcome) {
        counters.get(outcome).increment();
    }

    public void unreadable() {
        record(Outcome.SKIPPED);
    }
}
