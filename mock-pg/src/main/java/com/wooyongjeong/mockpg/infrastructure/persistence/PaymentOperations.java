package com.wooyongjeong.mockpg.infrastructure.persistence;

import com.wooyongjeong.mockpg.domain.PaymentConflict;
import com.wooyongjeong.mockpg.domain.PaymentMissing;
import com.wooyongjeong.mockpg.domain.PaymentResult;
import com.wooyongjeong.mockpg.domain.PaymentStore;
import com.wooyongjeong.mockpg.domain.Scenario;
import com.wooyongjeong.mockpg.domain.ScenarioConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentOperations implements PaymentStore {
    private final ScenarioJpaRepository scenarios;
    private final MockPaymentJpaRepository payments;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PaymentOperations(ScenarioJpaRepository scenarios, MockPaymentJpaRepository payments, JdbcTemplate jdbc,
            TransactionTemplate transactions) {
        this.scenarios = scenarios;
        this.payments = payments;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public PaymentResult charge(UUID key, long amount) {
        return run(key, () -> process(key, amount, false));
    }

    public PaymentResult close(UUID key, long amount) {
        return run(key, () -> process(key, amount, true));
    }

    public PaymentResult query(UUID key) {
        return run(key, () -> {
            MockPaymentEntity payment = payments.lock(key).orElseThrow(PaymentMissing::new);
            payment.resolve(now(), false);
            return payment.result(0);
        });
    }

    public ScenarioConfiguration configure(UUID key, ScenarioConfiguration configuration) {
        return run(key, () -> {
            ScenarioEntity setting = scenarios.lock(key).orElseThrow();
            if (payments.lock(key).isPresent() && !setting.configuration().equals(configuration)) {
                throw new PaymentConflict();
            }
            setting.set(configuration);
            return configuration;
        });
    }

    private PaymentResult process(UUID key, long amount, boolean close) {
        ScenarioConfiguration config = scenarios.lock(key).orElseThrow().configuration();
        MockPaymentEntity existing = payments.lock(key).orElse(null);
        Instant now = now();
        if (existing != null) {
            if (existing.amount() != amount) {
                throw new PaymentConflict();
            }
            existing.resolve(now, close);
            return existing.result(0);
        }
        MockPaymentEntity created = payments.saveAndFlush(new MockPaymentEntity(key, amount, config, now, close));
        int delay = !close && config.scenario() == Scenario.RESPONSE_LOSS ? config.delaySeconds() : 0;
        return created.result(delay);
    }

    private Instant now() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", Timestamp.class).toInstant();
    }

    private <T> T run(UUID key, java.util.function.Supplier<T> operation) {
        try {
            return transactions.execute(status -> {
                // The settings row serializes every operation for the same key before payment locking.
                if (scenarios.lock(key).isEmpty()) {
                    try {
                        scenarios.saveAndFlush(new ScenarioEntity(key));
                    } catch (DataIntegrityViolationException failure) {
                        if (isSettingsPrimaryKey(failure)) {
                            throw new DuplicateSetting(failure);
                        }
                        throw failure;
                    }
                }
                return operation.get();
            });
        } catch (DuplicateSetting failure) {
            // A concurrent insert won; its failed transaction has ended before this fresh lookup.
            return transactions.execute(status -> {
                scenarios.lock(key).orElseThrow();
                return operation.get();
            });
        }
    }

    private boolean isSettingsPrimaryKey(DataIntegrityViolationException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getSQLException() != null
                    && violation.getSQLException().getErrorCode() == 1062 && violation.getConstraintName() != null) {
                String name = violation.getConstraintName().replace("`", "").replace("'", "");
                return name.equalsIgnoreCase("PRIMARY") || name.equalsIgnoreCase("mock_scenario_settings.PRIMARY");
            }
        }
        return false;
    }

    private static class DuplicateSetting extends RuntimeException {
        DuplicateSetting(Throwable cause) {
            super(cause);
        }
    }
}
