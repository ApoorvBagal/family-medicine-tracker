package com.familymeds.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;

/** Plain unit test: runs everywhere, no Docker or database needed. */
class BootInfoContributorTest {

    private final BootLogRepository repository = mock(BootLogRepository.class);
    private final BootInfoContributor contributor = new BootInfoContributor(repository);

    @Test
    void reportsBootCountAndRecentBoots() {
        var entry = new BootLogRepository.BootEntry(3, OffsetDateTime.now(), "0.0.1", "host-a");
        when(repository.count()).thenReturn(3L);
        when(repository.recent(5)).thenReturn(List.of(entry));

        Info info = contribute();

        assertThat(info.get("boots")).isEqualTo(Map.of("total", 3L, "recent", List.of(entry)));
    }

    @Test
    void degradesGracefullyWhenDatabaseIsDown() {
        when(repository.count()).thenThrow(new RuntimeException("connection refused"));

        Info info = contribute();

        assertThat(info.get("boots")).isEqualTo(Map.of("error", "database unavailable"));
    }

    private Info contribute() {
        Info.Builder builder = new Info.Builder();
        contributor.contribute(builder);
        return builder.build();
    }
}
