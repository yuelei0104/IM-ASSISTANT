package com.yuelei.imchat.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ChatErrorHandler {
    public record Error(String message) { }

    @ExceptionHandler(ChatException.class)
    public ResponseEntity<Error> chat(ChatException ex) {
        return ResponseEntity.status(ex.status()).body(new Error(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Error> invalid(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new Error("消息格式不正确"));
    }
}
