package com.relayagents.shared.error;

public class ForbiddenException extends DomainException {

    public ForbiddenException(String code, String message) {
        super(code, message);
    }
}
