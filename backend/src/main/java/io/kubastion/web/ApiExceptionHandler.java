package io.kubastion.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

@RestControllerAdvice
public class ApiExceptionHandler {

    /** Nome di pod o configurazione non validi: e' colpa della richiesta. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    /** ssh non parte o il comando remoto fallisce: problema a monte, non nostro. */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<String> upstream(IOException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body("Comando remoto non eseguibile: " + e.getMessage());
    }
}
