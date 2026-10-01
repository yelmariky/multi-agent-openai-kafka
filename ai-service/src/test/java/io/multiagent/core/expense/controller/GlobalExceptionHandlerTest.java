package io.multiagent.core.expense.controller;

import io.multiagent.core.exception.InvalidReceiptFileException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsInvalidReceiptFileExceptionTo400() {
        var response = handler.handleInvalidReceiptFile(new InvalidReceiptFileException("Format non autorisé."));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "Format non autorisé.");
    }

    @Test
    void mapsMaxUploadSizeExceededTo400() {
        var response = handler.handleMaxUploadSize(new MaxUploadSizeExceededException(8 * 1024 * 1024));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error")).contains("volumineux");
    }
}
