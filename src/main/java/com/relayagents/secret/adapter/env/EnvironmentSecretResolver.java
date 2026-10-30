package com.relayagents.secret.adapter.env;

import com.relayagents.secret.application.SecretResolver;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Reads {@code RELAY_SECRET_<NAME>} from the environment (or the equivalent {@code relay.secret.<name>}
 * property). In Kubernetes these come from a Secret; swapping this class for a Vault or AWS Secrets
 * Manager lookup does not touch anything else.
 */
@Component
class EnvironmentSecretResolver implements SecretResolver {

    private final Environment environment;

    EnvironmentSecretResolver(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Optional<String> resolve(String name) {
        SecretResolver.checkName(name);
        String value = environment.getProperty("RELAY_SECRET_" + name);
        if (value == null) {
            value = environment.getProperty("relay.secret." + name.toLowerCase());
        }
        return Optional.ofNullable(value).filter(v -> !v.isBlank());
    }
}
