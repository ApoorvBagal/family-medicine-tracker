package com.familymeds.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.familymeds.shared.web.ApiException;

/**
 * First-time setup: creates the household and its owner (D12). Possible only once, and only with the
 * {@code SETUP_TOKEN} configured on the server, so a stranger who finds the URL first can't claim it.
 */
@Service
public class SetupService {

    public static final String DEFAULT_TIMEZONE = "Asia/Kolkata";
    /** BCrypt only uses the first 72 bytes of a password; longer ones would be silently truncated. */
    static final int MAX_PASSWORD_BYTES = 72;

    public record SetupCommand(String setupToken, String householdName, String displayName, String email,
            String password, String timezone) {
        @Override
        public String toString() {
            return "SetupCommand[householdName=" + householdName + "]"; // never print token or password
        }
    }

    private final IdentityRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final String configuredToken;

    public SetupService(IdentityRepository repository, PasswordEncoder passwordEncoder,
            @Value("${app.setup-token:}") String configuredToken) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.configuredToken = configuredToken;
    }

    public boolean setupRequired() {
        return !repository.isInstalled();
    }

    @Transactional
    public UUID setUp(SetupCommand cmd) {
        if (repository.isInstalled()) {
            throw alreadyDone();
        }
        if (configuredToken == null || configuredToken.isBlank()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SETUP_DISABLED",
                    "Setup is disabled: no SETUP_TOKEN is configured on the server.");
        }
        if (!constantTimeEquals(configuredToken, cmd.setupToken())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SETUP_TOKEN_INVALID", "The setup token is wrong.");
        }
        if (cmd.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_TOO_LONG",
                    "The password is too long (at most 72 bytes).");
        }
        String timezone = validTimezone(cmd.timezone());

        try {
            UUID householdId = repository.insertHousehold(cmd.householdName().trim(), timezone);
            UUID ownerId = repository.insertUser(householdId, AppUserDetailsService.normalizeEmail(cmd.email()),
                    cmd.displayName().trim(), passwordEncoder.encode(cmd.password()));
            repository.markInstalled(ownerId); // the single-row guard against concurrent setups
            return ownerId;
        } catch (DuplicateKeyException e) {
            // A concurrent setup won (same email -> unique index, otherwise installation's primary key).
            // The transaction rolls back everything created above.
            throw alreadyDone();
        }
    }

    private static String validTimezone(String tz) {
        if (tz == null || tz.isBlank()) {
            return DEFAULT_TIMEZONE;
        }
        try {
            return ZoneId.of(tz.trim()).getId();
        } catch (DateTimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TIMEZONE_INVALID", "Unknown time zone.");
        }
    }

    /** Compares SHA-256 digests so timing doesn't reveal how much of the token matched. */
    static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] a = sha.digest(expected.getBytes(StandardCharsets.UTF_8));
            byte[] b = MessageDigest.getInstance("SHA-256").digest(actual.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(a, b);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException alreadyDone() {
        return new ApiException(HttpStatus.CONFLICT, "SETUP_ALREADY_COMPLETED", "Setup has already been completed.");
    }
}
