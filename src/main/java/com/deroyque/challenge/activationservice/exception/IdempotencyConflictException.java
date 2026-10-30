package com.deroyque.challenge.activationservice.exception;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String mensagem) {
        super(mensagem);
    }
}
