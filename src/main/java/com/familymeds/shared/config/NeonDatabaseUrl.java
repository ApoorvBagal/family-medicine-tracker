package com.familymeds.shared.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Converts a libpq-style URL ({@code postgresql://user:pass@host/db?sslmode=require}), as written by
 * Neon, into the pieces the PostgreSQL JDBC driver needs: a {@code jdbc:postgresql://} URL with the
 * credentials passed separately.
 *
 * <p>TLS is tightened on the way: libpq's {@code sslmode=require} encrypts but does not verify the
 * server certificate, so it becomes {@code verify-full} (certificate + hostname check) using the JDK
 * trust store, which already trusts Neon's public CA.
 */
public record NeonDatabaseUrl(String jdbcUrl, String username, String password) {

    public static NeonDatabaseUrl parse(String url) {
        URI uri = URI.create(url.trim());
        String scheme = uri.getScheme();
        if (!"postgresql".equals(scheme) && !"postgres".equals(scheme)) {
            throw new IllegalArgumentException("Not a postgres URL (scheme '" + scheme + "')");
        }
        String userInfo = uri.getRawUserInfo();
        if (userInfo == null || !userInfo.contains(":")) {
            throw new IllegalArgumentException("Postgres URL must contain user:password");
        }
        int colon = userInfo.indexOf(':');
        String user = decode(userInfo.substring(0, colon));
        String password = decode(userInfo.substring(colon + 1));

        Map<String, String> params = new LinkedHashMap<>();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    params.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
                }
            }
        }
        String channelBinding = params.remove("channel_binding"); // libpq name; JDBC calls it channelBinding
        params.remove("sslmode");
        Map<String, String> jdbcParams = new LinkedHashMap<>();
        jdbcParams.put("sslmode", "verify-full");
        jdbcParams.put("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory");
        if (channelBinding != null) {
            jdbcParams.put("channelBinding", channelBinding);
        }
        jdbcParams.putAll(params);

        String hostPort = uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
        StringJoiner query = new StringJoiner("&", "?", "");
        jdbcParams.forEach((k, v) -> query.add(k + "=" + v));
        String jdbcUrl = "jdbc:postgresql://" + hostPort + uri.getRawPath() + query;
        return new NeonDatabaseUrl(jdbcUrl, user, password);
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    /** Never print the password, even by accident. */
    @Override
    public String toString() {
        return "NeonDatabaseUrl[jdbcUrl=" + jdbcUrl + ", username=" + username + ", password=***]";
    }
}
