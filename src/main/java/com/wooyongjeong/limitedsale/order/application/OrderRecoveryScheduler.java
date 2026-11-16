package com.wooyongjeong.limitedsale.order.application;

import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "limited-sale.recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderRecoveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(OrderRecoveryScheduler.class);
    private final OrderPaymentTransaction transaction;
    private final OrderPaymentService payments;
    private final int batchSize;
    private final ThreadPoolExecutor executor;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public OrderRecoveryScheduler(OrderPaymentTransaction transaction, OrderPaymentService payments,
            @Value("${limited-sale.recovery.batch-size:20}") int batchSize,
            @Value("${limited-sale.recovery.concurrency:4}") int concurrency) {
        if (batchSize <= 0 || concurrency <= 0) {
            throw new IllegalArgumentException("Recovery batch size and concurrency must be positive");
        }
        this.transaction = transaction;
        this.payments = payments;
        this.batchSize = batchSize;
        this.executor = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.SECONDS,
                new SynchronousQueue<>());
    }

    @Scheduled(fixedDelayString = "${limited-sale.recovery.interval:PT1S}")
    public void poll() {
        if (executor.isShutdown() || executor.getActiveCount() >= executor.getMaximumPoolSize()) {
            return;
        }
        for (long orderId : transaction.candidates(batchSize)) {
            if (!inFlight.add(orderId)) {
                continue;
            }
            try {
                executor.execute(() -> run(orderId));
            } catch (RejectedExecutionException ex) {
                inFlight.remove(orderId);
                break;
            }
        }
    }

    private void run(long orderId) {
        try {
            payments.recover(orderId);
        } catch (RuntimeException ex) {
            log.error("Recovery failed for order {}; lease expiry permits another attempt", orderId, ex);
        } finally {
            inFlight.remove(orderId);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
