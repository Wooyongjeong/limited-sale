package com.wooyongjeong.limitedsale.common;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 4-2: the integration test classpath also holds the Mock PG module. Its settings live in mock-pg.yml, so the only
 * application.yml here is the sales application's and a sales context started without an explicit config location
 * gets the sales settings (until 4-2 it silently got the Mock PG's).
 */
class ConfigurationClasspathIntegrationTest {
    private static List<URL> resources(String name) throws IOException {
        return Collections.list(ConfigurationClasspathIntegrationTest.class.getClassLoader().getResources(name));
    }

    private static String read(URL url) throws IOException {
        try (InputStream stream = url.openStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theOnlyApplicationYmlIsTheSalesApplicationsAndTheMockPgHasItsOwnFile() throws IOException {
        List<URL> applications = resources("application.yml");
        assertThat(applications).hasSize(1);
        assertThat(read(applications.getFirst())).contains("name: limited-sale").doesNotContain("mock-pg:");
        List<URL> mockPg = resources("mock-pg.yml");
        assertThat(mockPg).hasSize(1);
        assertThat(read(mockPg.getFirst())).contains("mock-pg");
    }
}
