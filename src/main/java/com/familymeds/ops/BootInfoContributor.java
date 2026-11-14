package com.familymeds.ops;

import java.util.Map;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

/**
 * Adds boot history to {@code /actuator/info}. If {@code boots.total} keeps growing across
 * restarts and redeploys, the database is persistent.
 */
@Component
public class BootInfoContributor implements InfoContributor {

    private final BootLogRepository repository;

    public BootInfoContributor(BootLogRepository repository) {
        this.repository = repository;
    }

    @Override
    public void contribute(Info.Builder builder) {
        try {
            builder.withDetail("boots", Map.of(
                    "total", repository.count(),
                    "recent", repository.recent(5)));
        } catch (RuntimeException e) {
            builder.withDetail("boots", Map.of("error", "database unavailable"));
        }
    }
}
