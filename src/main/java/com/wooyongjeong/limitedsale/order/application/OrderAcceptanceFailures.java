package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.common.observation.RequestIdFilter;
import com.wooyongjeong.limitedsale.order.domain.OrderAcceptanceStore;
import com.wooyongjeong.limitedsale.order.domain.ProcessingFailure;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Ends an acceptance that cannot be processed as PROCESSING_FAILED, in its own transaction. Nothing of the failed
 * processing is left behind: that transaction rolled back, so no stock was taken and no order exists. An acceptance
 * that is no longer ACCEPTED (another delivery finished it meanwhile) is left as it is. The failure is counted once the
 * transaction has committed, and the acceptance's request id goes into the log context for whatever is logged next.
 */
@Service
public class OrderAcceptanceFailures {
    private final OrderAcceptanceStore acceptances;
    private final AcceptanceProcessingMetrics metrics;
    private final Clock clock;

    public OrderAcceptanceFailures(OrderAcceptanceStore acceptances, AcceptanceProcessingMetrics metrics,
            Clock clock) {
        this.acceptances = acceptances;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Returns whether this call stored the failure. */
    @Transactional
    public boolean fail(UUID acceptanceId, ProcessingFailure reason) {
        var acceptance = acceptances.lockForProcessing(acceptanceId).orElse(null);
        if (acceptance == null || acceptance.finished()) {
            return false;
        }
        if (acceptance.requestId() != null) {
            MDC.put(RequestIdFilter.MDC_KEY, acceptance.requestId());
        }
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        acceptances.finishFailed(acceptanceId, reason, now);
        Duration sinceAcceptance = Duration.between(acceptance.acceptedAt(), now);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                metrics.failed(reason, sinceAcceptance);
            }
        });
        return true;
    }
}
