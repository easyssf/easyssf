package org.easyssf.receiver.event;

/**
 * Thrown when at least one {@link SsfEventHandler} failed to handle a verified SET.
 */
public class SsfEventHandlingException extends RuntimeException {

    public SsfEventHandlingException(String message, Throwable cause) {
        super(message, cause);
    }

}
