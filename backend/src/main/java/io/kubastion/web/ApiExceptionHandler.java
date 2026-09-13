package io.kubastion.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

@RestControllerAdvice
public class ApiExceptionHandler {

    /** Invalid pod name or configuration: the request is at fault. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    /** ssh or the remote command failed: the problem is upstream, not here. */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<String> upstream(IOException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body("Remote command could not be run: " + e.getMessage());
    }
}
