package com.braintwinx.exception;

/**
 * Thrown when a lifecycle transition that the domain forbids is attempted.
 *
 * <p>Brief section 52 requires that status changes be deterministic and that arbitrary
 * changes be impossible. Rejecting an illegal transition with a typed, specific
 * exception — rather than allowing the write and discovering the inconsistency later —
 * is what keeps a scan or job from reaching a state its own state machine does not
 * permit.
 *
 * <p>This is a programming or concurrency error, not user input error. It maps to a
 * 409 Conflict at the API boundary, never to a message that exposes internals.
 */
public class InvalidStateTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String entityType;
    private final String fromState;
    private final String toState;

    public InvalidStateTransitionException(String entityType, String fromState, String toState) {
        super("Illegal %s transition: %s -> %s".formatted(entityType, fromState, toState));
        this.entityType = entityType;
        this.fromState = fromState;
        this.toState = toState;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getFromState() {
        return fromState;
    }

    public String getToState() {
        return toState;
    }
}
