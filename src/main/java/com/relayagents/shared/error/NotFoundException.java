package com.relayagents.shared.error;

public class NotFoundException extends DomainException {

    public NotFoundException(String code, String message) {
        super(code, message);
    }
}
