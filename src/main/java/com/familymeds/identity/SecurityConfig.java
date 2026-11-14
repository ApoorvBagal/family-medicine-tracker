package com.familymeds.identity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

import com.familymeds.shared.web.ProblemResponses;

/**
 * Session-cookie security for a single-page app served from the same origin.
 * <ul>
 *   <li>Everything under {@code /api/**} needs login, except the setup/login/csrf endpoints.</li>
 *   <li>Other paths (the PWA's static files, from M1c) are public; no data lives outside {@code /api}.</li>
 *   <li>CSRF: token in a readable {@code XSRF-TOKEN} cookie, sent back by the app in the
 *       {@code X-XSRF-TOKEN} header on every POST/PUT/PATCH/DELETE.</li>
 *   <li>Errors are problem+json; no redirects to a login page, no browser Basic-auth popups.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data:; font-src 'self'; connect-src 'self'; manifest-src 'self'; object-src 'none'; "
            + "base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CookieCsrfTokenRepository csrfTokenRepository)
            throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/setup-status", "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/setup", "/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/**", "/v3/api-docs", "/v3/api-docs/**").authenticated()
                        .anyRequest().permitAll())
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> ProblemResponses.write(res, 401,
                                "Unauthorized", "Please log in.", "UNAUTHENTICATED"))
                        .accessDeniedHandler((req, res, ex) -> {
                            if (ex instanceof CsrfException) {
                                ProblemResponses.write(res, 403, "Forbidden",
                                        "Missing or invalid CSRF token. Reload the app and try again.",
                                        "CSRF_TOKEN_INVALID");
                            } else {
                                ProblemResponses.write(res, 403, "Forbidden", "Not allowed.", "FORBIDDEN");
                            }
                        }))
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout") // POST only, CSRF-protected
                        .deleteCookies("SESSION")
                        .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
                .headers(h -> h
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        // The built app has no inline scripts or styles, so everything can be locked to 'self'.
                        // This blocks injected scripts even if an XSS bug slipped in.
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                                "camera=(), microphone=(), geolocation=(), payment=(), usb=()")));
        return http.build();
    }

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse(); // the app must read it
        repository.setCookieCustomizer(c -> c.secure(true).sameSite("Lax").path("/"));
        return repository;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository(); // the session itself is stored in Postgres
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder(); // bcrypt, with {id} prefix for upgrades
    }

    @Bean
    AuthenticationManager authenticationManager(AppUserDetailsService users, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider); // erases the password hash from the principal after login
    }
}
