package com.familymeds.ops;

import java.net.InetAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Writes one {@code app_boot_log} row each time the application finishes starting. */
@Component
public class BootRecorder {

    private static final Logger log = LoggerFactory.getLogger(BootRecorder.class);

    private final BootLogRepository repository;
    private final String version;

    public BootRecorder(BootLogRepository repository, ObjectProvider<BuildProperties> buildProperties) {
        this.repository = repository;
        BuildProperties build = buildProperties.getIfAvailable();
        this.version = build != null ? build.getVersion() : "dev";
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            repository.record(version, host);
            log.info("Boot recorded: version={} host={} totalBoots={}", version, host, repository.count());
        } catch (Exception e) {
            // Never fail startup over a diagnostic row; the health check will report DB problems.
            log.warn("Could not record boot", e);
        }
    }
}
