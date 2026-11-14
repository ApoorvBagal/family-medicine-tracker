package com.familymeds.identity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The logged-in user, as stored in the (database-backed) session. Holds the household id so every
 * query can be scoped from the session, never from request input. The password hash is erased after
 * login, so it never lands in the session table.
 */
public final class AppUserPrincipal implements UserDetails, CredentialsContainer {

    private static final List<GrantedAuthority> OWNER = List.of(new SimpleGrantedAuthority("ROLE_OWNER"));

    private final UUID userId;
    private final UUID householdId;
    private final String email;
    private String passwordHash;

    public AppUserPrincipal(UUID userId, UUID householdId, String email, String passwordHash) {
        this.userId = userId;
        this.householdId = householdId;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public UUID userId() {
        return userId;
    }

    public UUID householdId() {
        return householdId;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return OWNER;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }

    @Override
    public String toString() {
        return "AppUserPrincipal[userId=" + userId + ", householdId=" + householdId + "]";
    }
}
