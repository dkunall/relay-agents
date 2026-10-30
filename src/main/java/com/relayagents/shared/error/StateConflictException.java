package com.relayagents.shared.error;

public class StateConflictException extends DomainException {

    public StateConflictException(String code, String message) {
        super(code, message);
    }
}
