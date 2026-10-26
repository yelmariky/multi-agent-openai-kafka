package io.multiagent.core.expense.controller;

import io.multiagent.core.exception.InvalidReceiptFileException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

@RestControllerAdvice
@Slf4j
@lombok.RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final io.multiagent.core.i18n.Messages messages;

    @ExceptionHandler(InvalidReceiptFileException.class)
    public ResponseEntity<Map<String, String>> handleInvalidReceiptFile(InvalidReceiptFileException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", messages.get("upload.error.tooLarge")));
    }
}
