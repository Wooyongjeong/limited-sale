package com.wooyongjeong.limitedsale.schema;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MigrationResourcesTest {
    @Test
    void salesMigrationIsAvailable() {
        assertThat(getClass().getResource("/db/migration/V1__sales_schema.sql")).isNotNull();
    }
}
