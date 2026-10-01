package io.multiagent.expense.service;

import io.multiagent.expense.exception.InvalidReceiptFileException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceiptFileValidatorTest {

    private final ReceiptFileValidator validator = new ReceiptFileValidator();

    private static final byte[] PDF_BYTES = new byte[]{0x25, 0x50, 0x44, 0x46, '-', '1', '.', '4'};
    private static final byte[] JPEG_BYTES = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};
    private static final byte[] PNG_BYTES = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    @Test
    void acceptsValidPdf() {
        var file = new MockMultipartFile("file", "facture.pdf", "application/pdf", PDF_BYTES);
        assertThatCode(() -> validator.validate(file)).doesNotThrowAnyException();
    }

    @Test
    void acceptsValidJpeg() {
        var file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", JPEG_BYTES);
        assertThatCode(() -> validator.validate(file)).doesNotThrowAnyException();
    }

    @Test
    void acceptsValidPng() {
        var file = new MockMultipartFile("file", "scan.png", "image/png", PNG_BYTES);
        assertThatCode(() -> validator.validate(file)).doesNotThrowAnyException();
    }

    @Test
    void rejectsEmptyFile() {
        var file = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);
        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(InvalidReceiptFileException.class)
                .hasMessageContaining("vide");
    }

    @Test
    void rejectsNullFile() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(InvalidReceiptFileException.class);
    }

    @Test
    void rejectsDisallowedExtension() {
        var file = new MockMultipartFile("file", "malware.exe", "application/octet-stream", PDF_BYTES);
        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(InvalidReceiptFileException.class)
                .hasMessageContaining("Format non autorisé");
    }

    @Test
    void rejectsExeRenamedAsPdf() {
        // En-tête binaire MZ (exécutable Windows) mais extension .pdf — doit être détecté par les magic bytes
        byte[] exeBytes = new byte[]{'M', 'Z', (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00};
        var file = new MockMultipartFile("file", "invoice.pdf", "application/pdf", exeBytes);
        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(InvalidReceiptFileException.class)
                .hasMessageContaining("correspond pas");
    }

    @Test
    void rejectsExtensionMismatchingRealContent() {
        // Contenu JPEG réel mais renommé en .png
        var file = new MockMultipartFile("file", "photo.png", "image/png", JPEG_BYTES);
        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(InvalidReceiptFileException.class)
                .hasMessageContaining("correspond pas");
    }

    @Test
    void rejectsOversizedFile() {
        byte[] big = new byte[9 * 1024 * 1024]; // 9 Mo > 8 Mo max
        System.arraycopy(PDF_BYTES, 0, big, 0, PDF_BYTES.length);
        var file = new MockMultipartFile("file", "gros.pdf", "application/pdf", big);
        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(InvalidReceiptFileException.class)
                .hasMessageContaining("volumineux");
    }

}
