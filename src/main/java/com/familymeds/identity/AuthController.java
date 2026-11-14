package com.familymeds.identity;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.familymeds.shared.web.ApiException;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record SetupStatus(boolean setupRequired) {
    }

    public record SetupRequest(
            @NotBlank String setupToken,
            @NotBlank @Size(max = 100) String householdName,
            @NotBlank @Size(max = 100) String displayName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotNull @Size(min = 12, max = 64, message = "must be 12 to 64 characters") String password,
            @Size(max = 64) String timezone) {
        @Override
        public String toString() {
            return "SetupRequest[householdName=" + householdName + "]";
        }
    }

    public record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 128) String password) {
        @Override
        public String toString() {
            return "LoginRequest[]";
        }
    }

    public record Me(UserView user, HouseholdView household) {
    }

    public record UserView(UUID id, String email, String displayName) {
    }

    public record HouseholdView(UUID id, String name, String timezone, int defaultWarnDays) {
    }

    private final SetupService setupService;
    private final IdentityRepository repository;
    private final AuthenticationManager authenticationManager;
    private final AppUserDetailsService userDetailsService;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfAuthenticationStrategy csrfRotation;
    private final LoginAttemptLimiter limiter;

    public AuthController(SetupService setupService, IdentityRepository repository,
            AuthenticationManager authenticationManager, AppUserDetailsService userDetailsService,
            SecurityContextRepository securityContextRepository, CookieCsrfTokenRepository csrfTokenRepository,
            LoginAttemptLimiter limiter) {
        this.setupService = setupService;
        this.repository = repository;
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.securityContextRepository = securityContextRepository;
        this.csrfRotation = new CsrfAuthenticationStrategy(csrfTokenRepository);
        this.limiter = limiter;
    }

    @GetMapping("/setup-status")
    public SetupStatus setupStatus() {
        return new SetupStatus(setupService.setupRequired());
    }

    /** Makes sure the XSRF-TOKEN cookie is set; the app calls this once before its first POST. */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf(CsrfToken token) {
        token.getToken(); // loading the deferred token writes the cookie
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/setup")
    public ResponseEntity<Me> setup(@Valid @RequestBody SetupRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        String ip = request.getRemoteAddr();
        // Rate-limit token guessing like password guessing.
        limiter.blockedFor(ip, "setup").ifPresent(wait -> {
            throw tooMany(wait);
        });
        try {
            setupService.setUp(new SetupService.SetupCommand(body.setupToken(), body.householdName(),
                    body.displayName(), body.email(), body.password(), body.timezone()));
        } catch (ApiException e) {
            if ("SETUP_TOKEN_INVALID".equals(e.code())) {
                limiter.recordFailure(ip, "setup");
            }
            throw e;
        }
        AppUserPrincipal principal = (AppUserPrincipal) userDetailsService.loadUserByUsername(body.email());
        principal.eraseCredentials();
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                principal.getAuthorities());
        startSession(auth, request, response);
        repository.recordLogin(principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(me(principal));
    }

    @PostMapping("/login")
    public Me login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        String ip = request.getRemoteAddr();
        String email = AppUserDetailsService.normalizeEmail(body.email());
        limiter.blockedFor(ip, email).ifPresent(wait -> {
            throw tooMany(wait);
        });
        Authentication auth;
        try {
            auth = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(email, body.password()));
        } catch (AuthenticationException e) {
            limiter.recordFailure(ip, email);
            // Same answer whether the email exists or not.
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect.");
        }
        limiter.recordSuccess(ip, email);
        startSession(auth, request, response);
        AppUserPrincipal principal = (AppUserPrincipal) auth.getPrincipal();
        repository.recordLogin(principal.userId());
        return me(principal);
    }

    @GetMapping("/me")
    public Me me(@AuthenticationPrincipal AppUserPrincipal principal) {
        return toMe(principal);
    }

    private Me toMe(AppUserPrincipal principal) {
        var user = repository.findUserById(principal.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Please log in."));
        var household = repository.findHousehold(user.householdId()).orElseThrow();
        return new Me(new UserView(user.id(), user.email(), user.displayName()),
                new HouseholdView(household.id(), household.name(), household.timezone(), household.defaultWarnDays()));
    }

    /**
     * Stores the authentication in the (Postgres-backed) session. A fresh session id is issued
     * (prevents session fixation) and the CSRF token is rotated.
     */
    private void startSession(Authentication auth, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        csrfRotation.onAuthentication(auth, request, response);
    }

    private static ApiException tooMany(java.time.Duration wait) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                "Too many failed attempts. Try again later.", wait);
    }
}
