package com.wooyongjeong.mockpg.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ScenarioConfigurationTest {
    @Test
    void delayDefaultsAreNormalizedBeforeComparison() {
        assertThat(ScenarioConfiguration.of(Scenario.DELAY, null))
                .isEqualTo(ScenarioConfiguration.of(Scenario.DELAY, 10));
        assertThat(ScenarioConfiguration.of(Scenario.RESPONSE_LOSS, null))
                .isEqualTo(ScenarioConfiguration.of(Scenario.RESPONSE_LOSS, 5));
    }

    @Test
    void immediateScenariosRejectDelay() {
        assertThatIllegalArgumentException().isThrownBy(() -> ScenarioConfiguration.of(Scenario.SUCCESS, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> ScenarioConfiguration.of(Scenario.DECLINE, 1));
    }

    @Test
    void delayMustBeWithinApprovedBounds() {
        assertThatIllegalArgumentException().isThrownBy(() -> ScenarioConfiguration.of(Scenario.DELAY, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> ScenarioConfiguration.of(Scenario.RESPONSE_LOSS, 601));
    }
}
