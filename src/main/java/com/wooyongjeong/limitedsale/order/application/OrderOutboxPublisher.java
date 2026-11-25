package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.OutboxLeadership;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Sends the outbox rows of accepted orders to Kafka from one background thread, on one instance at a time.
 * An instance that is not the publisher asks for the right once a second; the one that holds it publishes batches
 * and checks before each batch that it still holds it. It runs only when the asynchronous order API and the
 * publisher are both switched on. It is not part of health: a broker outage must not take the instance out of
 * rotation, because acceptance does not need the broker.
 */
@Component
@ConditionalOnProperty(name = {"limited-sale.order.async.enabled", "limited-sale.order.outbox-publisher.enabled"},
    havingValue = "true")
public class OrderOutboxPublisher implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(OrderOutboxPublisher.class);
    private static final Duration WAIT_FOR_LEADERSHIP = Duration.ofSeconds(1);
    private static final Duration IDLE = Duration.ofMillis(100);
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);
    private static final Duration LONGEST_BACKOFF = Duration.ofSeconds(10);
    private static final Duration STOP_WAIT = Duration.ofSeconds(20);

    private final OutboxLeadership leadership;
    private final OutboxBatch batch;
    private final OrderStallDetector stalls;
    private final Duration stallScanInterval;
    private long lastStallScan = System.nanoTime();
    private final AtomicInteger leader = new AtomicInteger();
    private volatile Thread worker;

    public OrderOutboxPublisher(OutboxLeadership leadership, OutboxBatch batch, OrderStallDetector stalls,
            MeterRegistry registry,
            @Value("${limited-sale.order.stall.scan-interval:PT10S}") Duration stallScanInterval) {
        this.leadership = leadership;
        this.batch = batch;
        this.stalls = stalls;
        this.stallScanInterval = stallScanInterval;
        Gauge.builder("order.outbox.publisher.leader", leader, AtomicInteger::get)
            .description("1 while this instance holds the right to publish the outbox, otherwise 0").register(registry);
    }

    @Override
    public synchronized void start() {
        if (worker != null) {
            return;
        }
        worker = Thread.ofPlatform().name("order-outbox-publisher").start(this::run);
    }

    @Override
    public void stop() {
        Thread running;
        synchronized (this) {
            running = worker;
            worker = null;
        }
        if (running == null) {
            return;
        }
        running.interrupt();
        try {
            // The thread gives the right up as it ends; waiting here means a closed context has done so already.
            running.join(STOP_WAIT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return worker != null;
    }

    private void run() {
        OutboxBackoff backoff = new OutboxBackoff(FIRST_BACKOFF, LONGEST_BACKOFF);
        boolean publishing = false;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (!publishing) {
                        publishing = leadership.tryAcquire();
                        if (!publishing) {
                            Thread.sleep(WAIT_FOR_LEADERSHIP);
                            continue;
                        }
                        leader.set(1);
                        log.info("This instance is now the outbox publisher");
                    }
                    if (!leadership.stillHeld()) {
                        publishing = stepDown("The outbox publisher right is gone");
                        Thread.sleep(WAIT_FOR_LEADERSHIP);
                        continue;
                    }
                    scanForStallsWhenDue();
                    OutboxBatch.Result result = batch.publish();
                    if (result.failedAny()) {
                        Duration wait = backoff.failure();
                        log.warn("{} of {} outbox rows were not sent; trying again in {} s", result.failed(),
                            result.read(), wait.toSeconds());
                        Thread.sleep(wait);
                    } else {
                        backoff.success();
                        if (result.empty()) {
                            Thread.sleep(IDLE);
                        }
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException failure) {
                    if (Thread.currentThread().isInterrupted()) {
                        continue;
                    }
                    Duration wait = backoff.failure();
                    log.warn("Outbox publishing failed; trying again in {} s: {}", wait.toSeconds(), failure.toString());
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } finally {
            stepDown("The outbox publisher stopped");
        }
    }

    /** The stall check rides on the publisher thread, so it also runs on one instance only. */
    private void scanForStallsWhenDue() {
        if (System.nanoTime() - lastStallScan < stallScanInterval.toNanos()) {
            return;
        }
        lastStallScan = System.nanoTime();
        try {
            int found = stalls.scan();
            if (found > 0) {
                log.warn("Found {} stalled order acceptances; republished or failed them", found);
            }
        } catch (RuntimeException failure) {
            // Publishing goes on; the next check tries again.
            log.warn("The stall check failed: {}", failure.toString());
        }
    }

    private boolean stepDown(String reason) {
        leadership.release();
        if (leader.getAndSet(0) == 1) {
            log.info("{}; this instance is no longer the outbox publisher", reason);
        }
        return false;
    }
}
