package com.familymeds.identity;

import java.util.Locale;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final IdentityRepository repository;

    public AppUserDetailsService(IdentityRepository repository) {
        this.repository = repository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        return repository.findUserByEmail(normalizeEmail(email))
                .map(u -> new AppUserPrincipal(u.id(), u.householdId(), u.email(), u.passwordHash()))
                .orElseThrow(() -> new UsernameNotFoundException("not found")); // reported to clients generically
    }

    static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
