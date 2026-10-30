package com.relayagents.shared.error;

public class BusinessRuleException extends DomainException {

    public BusinessRuleException(String code, String message) {
        super(code, message);
    }
}
