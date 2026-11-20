package com.wooyongjeong.limitedsale.common.observation;

import java.util.Map;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The actuator endpoints live on the management port. This keeps the public port's health URL working
 * and exposes only the status, never component details or metrics.
 */
@RestController
@ConditionalOnWebApplication
public class PublicHealthController {
    private final HealthEndpoint health;
    private final HealthEndpointGroups groups;

    public PublicHealthController(HealthEndpoint health, HealthEndpointGroups groups) {
        this.health = health;
        this.groups = groups;
    }

    @GetMapping(path = "/actuator/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> health() {
        Status status = health.health().getStatus();
        return ResponseEntity.status(groups.getPrimary().getHttpCodeStatusMapper().getStatusCode(status)).body(Map.of("status", status.getCode()));
    }
}
