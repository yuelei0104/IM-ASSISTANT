package com.yuelei.imchat.exception;

import org.springframework.http.HttpStatus;

public class ChatException extends RuntimeException {
    private final HttpStatus status;
    public ChatException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
    public HttpStatus status() { return status; }
}
