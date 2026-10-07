package com.almahwar.api.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Public health endpoints for load balancers / monitoring. They say only UP / READY / NOT_READY: no version, host,
 * database name, schema or error text (those are in the server log).
 * <ul>
 *   <li>{@code /api/v1/health} — liveness: the process answers.</li>
 *   <li>{@code /api/v1/health/ready} — readiness: both databases are reachable and compatible; checked at most every
 *       {@code almahwar.api.health.ready-cache} (5 s) so the public endpoint cannot be used to load the database.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "Health")
public class HealthController {

    public record HealthResponse(String status) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(HealthController.class);

    private final SchemaCompatibilityChecker checker;
    private final ApiSchemaCompatibilityChecker sessions;
    private final Duration readyCache;
    private final Clock clock;
    private volatile Instant checkedAt = Instant.EPOCH;
    private volatile boolean ready;

    public HealthController(SchemaCompatibilityChecker checker, ApiSchemaCompatibilityChecker sessions,
                            @Value("${almahwar.api.health.ready-cache:5s}") Duration readyCache) {
        this.checker = checker;
        this.sessions = sessions;
        this.readyCache = readyCache;
        this.clock = Clock.systemUTC();
    }

    @GetMapping
    @Operation(summary = "Liveness (public)")
    public ResponseEntity<HealthResponse> live() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new HealthResponse("UP"));
    }

    @GetMapping("/ready")
    @Operation(summary = "Readiness (public): database reachable and schema compatible")
    public ResponseEntity<HealthResponse> ready() {
        Instant now = clock.instant();
        if (Duration.between(checkedAt, now).compareTo(readyCache) >= 0) {
            synchronized (this) {
                if (Duration.between(checkedAt, now).compareTo(readyCache) >= 0) {
                    SchemaCompatibilityChecker.Result result = checker.check();
                    if (!result.compatible()) {
                        LOG.warn("Not ready: {} - {}", result.status(), result.detail());
                    }
                    ready = result.compatible() && sessions.compatible();
                    checkedAt = now;
                }
            }
        }
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(new HealthResponse(ready ? "READY" : "NOT_READY"));
    }
}
