package com.familymeds;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.familymeds.support.TestDatabase;

/**
 * The React app (built into the jar by the Maven build) is served correctly and securely.
 * Uses a real embedded server and HTTP client: the "/" → index.html forward and the .webmanifest
 * content type only happen in a real servlet container, not in MockMvc.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("com.familymeds.support.TestDatabase#available")
class StaticSiteIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String header(HttpResponse<?> res, String name) {
        return res.headers().firstValue(name).orElse("");
    }

    @Test
    void rootServesTheApp_withStrictSecurityHeaders_andNoCaching() throws Exception {
        var res = get("/");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("<div id=\"root\"></div>");
        assertThat(header(res, "Content-Security-Policy")).contains("script-src 'self'").contains("frame-ancestors 'none'");
        assertThat(header(res, "X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(header(res, "Permissions-Policy")).contains("camera=()");
        assertThat(header(res, "Cache-Control")).contains("no-cache");
    }

    @Test
    void hashedAssets_areCachedForAYear() throws Exception {
        var res = get(builtAsset("\\.js"));
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(header(res, "Cache-Control")).contains("max-age=31536000").contains("immutable");
        assertThat(header(res, "Content-Type")).contains("javascript");
    }

    @Test
    void manifest_isServedWithTheManifestContentType_andMakesTheAppInstallable() throws Exception {
        var res = get("/manifest.webmanifest");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(header(res, "Content-Type")).contains("application/manifest+json");
        assertThat(res.body()).contains("\"display\": \"standalone\"").contains("\"start_url\": \"/\"")
                .contains("\"sizes\": \"192x192\"").contains("\"purpose\": \"maskable\"");
        var icon = get("/icons/icon-512.png");
        assertThat(icon.statusCode()).isEqualTo(200);
        assertThat(header(icon, "Content-Type")).isEqualTo("image/png");
    }

    @Test
    void apiErrors_stayJson() throws Exception {
        var res = get("/api/v1/auth/me");
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(header(res, "Content-Type")).contains("application/problem+json");
        assertThat(res.body()).contains("\"code\":\"UNAUTHENTICATED\"");
    }

    /** Reads the hashed asset name from the built index.html (it changes with every build). */
    private static String builtAsset(String extensionRegex) throws IOException {
        try (InputStream in = StaticSiteIntegrationTest.class.getResourceAsStream("/static/index.html")) {
            assertThat(in).as("frontend must be built into target/classes/static by the Maven build").isNotNull();
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("(/assets/[^\"]+" + extensionRegex + ")\"").matcher(html);
            assertThat(m.find()).isTrue();
            return m.group(1);
        }
    }
}
