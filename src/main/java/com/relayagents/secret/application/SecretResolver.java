package com.relayagents.secret.application;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Secrets are referenced by name in definitions ({@code {{secret.ORDERS_API_TOKEN}}}) and only resolved at
 * the moment of use. The value never goes into a definition, an event or a log line.
 */
public interface SecretResolver {

    Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

    Optional<String> resolve(String name);

    static void checkName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Secret names must match " + NAME.pattern());
        }
    }
}
