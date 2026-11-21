package com.copymylife.copymylife.exception;

public class ExpiredResetTokenException extends RuntimeException {

    public ExpiredResetTokenException(String message) {
        super(message);
    }
}