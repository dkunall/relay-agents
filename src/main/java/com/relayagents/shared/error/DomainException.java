package com.relayagents.shared.error;

/**
 * Base for failures the API reports to clients. {@code code} becomes the problem type, so keep it stable.
 */
public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
