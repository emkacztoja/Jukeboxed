package com.jukeboxed.auth;

public class MachineIdUnavailableException extends RuntimeException {
    public MachineIdUnavailableException(String message) {
        super(message);
    }

    public MachineIdUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}