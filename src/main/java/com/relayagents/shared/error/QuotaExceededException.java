package com.relayagents.shared.error;

public class QuotaExceededException extends DomainException {

    public QuotaExceededException(String code, String message) {
        super(code, message);
    }
}
