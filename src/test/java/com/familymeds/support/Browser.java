package com.familymeds.support;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Behaves like the real app in a browser: keeps cookies between requests (session + XSRF-TOKEN)
 * and echoes the XSRF-TOKEN cookie in the X-XSRF-TOKEN header on writes.
 */
public class Browser {

    private final MockMvc mvc;
    private final String ip;
    private final Map<String, String> cookies = new LinkedHashMap<>();

    public Browser(MockMvc mvc, String ip) {
        this.mvc = mvc;
        this.ip = ip;
    }

    public ResultActions get(String path) throws Exception {
        return perform(MockMvcRequestBuilders.get(path), false);
    }

    /** POST with the CSRF header, like the real app. Fetches the CSRF cookie first if needed. */
    public ResultActions post(String path, String json) throws Exception {
        if (!cookies.containsKey("XSRF-TOKEN")) {
            get("/api/v1/auth/csrf");
        }
        return perform(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(json), true);
    }

    /** PUT with the CSRF header and an optional If-Match (optimistic locking). */
    public ResponseActionsWithEtag put(String path, String json, String ifMatch) throws Exception {
        if (!cookies.containsKey("XSRF-TOKEN")) {
            get("/api/v1/auth/csrf");
        }
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.put(path)
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return new ResponseActionsWithEtag(perform(request, true));
    }

    /** Small wrapper so tests can read the ETag of a response. */
    public record ResponseActionsWithEtag(ResultActions actions) {
        public ResultActions andExpect(org.springframework.test.web.servlet.ResultMatcher m) throws Exception {
            return actions.andExpect(m);
        }

        public String etag() {
            return actions.andReturn().getResponse().getHeader("ETag");
        }
    }

    /** POST without a CSRF header: what a malicious cross-site form could send. */
    public ResultActions postWithoutCsrf(String path, String json) throws Exception {
        return perform(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(json), false);
    }

    public String cookie(String name) {
        return cookies.get(name);
    }

    public Browser withCookie(String name, String value) {
        cookies.put(name, value);
        return this;
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, boolean withCsrf) throws Exception {
        request.with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
        if (!cookies.isEmpty()) {
            request.cookie(cookies.entrySet().stream().map(e -> new Cookie(e.getKey(), e.getValue()))
                    .toArray(Cookie[]::new));
        }
        if (withCsrf && cookies.containsKey("XSRF-TOKEN")) {
            request.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
        }
        ResultActions result = mvc.perform(request);
        for (String header : result.andReturn().getResponse().getHeaders("Set-Cookie")) {
            store(header);
        }
        return result;
    }

    private void store(String setCookie) {
        String first = setCookie.split(";", 2)[0];
        int eq = first.indexOf('=');
        String name = first.substring(0, eq).trim();
        String value = first.substring(eq + 1).trim();
        if (value.isEmpty() || setCookie.toLowerCase(Locale.ROOT).contains("max-age=0")) {
            cookies.remove(name);
        } else {
            cookies.put(name, value);
        }
    }
}
