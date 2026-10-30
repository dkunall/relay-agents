package com.relayagents.shared.error;

public class InvalidRequestException extends DomainException {

    public InvalidRequestException(String code, String message) {
        super(code, message);
    }
}
