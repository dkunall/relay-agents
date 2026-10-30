package com.relayagents.run.domain;

import com.relayagents.shared.error.StateConflictException;

public class IllegalTransitionException extends StateConflictException {

    public IllegalTransitionException(String what, String from, String to) {
        super("illegal-transition", "A " + what + " cannot go from " + from + " to " + to);
    }
}
