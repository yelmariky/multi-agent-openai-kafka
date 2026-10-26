package io.multiagent.core.policy.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Extrait le texte d'un PDF de politique (document "born-digital", pas un scan) via PDFBox
 * ({@link PDFTextStripper}) — volontairement distinct d'{@code OcrService} qui ne fait que
 * rasteriser-puis-OCR la première page d'une image/PDF scanné, impropre à un document texte
 * multi-pages comme une politique de remboursement.
 */
@Service
@Slf4j
@lombok.RequiredArgsConstructor
public class PolicyPdfExtractionService {

    private final io.multiagent.core.i18n.Messages messages;

    // Aligné avec spring.servlet.multipart.max-file-size (application.yml).
    private static final long MAX_SIZE_BYTES = 8L * 1024 * 1024;

    // Protection DoS : une politique tient toujours en quelques pages. Un PDF pathologique (ex:
    // objets répétés/imbriqués) peut rester sous 8 Mo tout en ayant un nombre de pages démesuré —
    // ce plafond borne le coût de PDFTextStripper indépendamment de la taille en octets.
    private static final int MAX_PAGES = 30;

    /** @throws IllegalArgumentException si le fichier est invalide, corrompu, protégé ou sans texte extractible. */
    public String extractText(MultipartFile file) {
        validate(file);
        try (PDDocument doc = PDDocument.load(file.getInputStream())) {
            if (doc.isEncrypted()) {
                throw new IllegalArgumentException(messages.get("policy.error.pdfEncrypted"));
            }
            if (doc.getNumberOfPages() > MAX_PAGES) {
                throw new IllegalArgumentException(
                        messages.get("policy.error.pdfTooManyPages", String.valueOf(doc.getNumberOfPages()), String.valueOf(MAX_PAGES)));
            }
            String text = new PDFTextStripper().getText(doc).strip();
            if (text.isBlank()) {
                throw new IllegalArgumentException(
                        messages.get("policy.error.pdfNoText"));
            }
            return text;
        } catch (IOException e) {
            throw new IllegalArgumentException(messages.get("policy.error.pdfUnreadable", e.getMessage()));
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(messages.get("upload.error.empty"));
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException(messages.get("upload.error.tooLarge"));
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException(messages.get("policy.error.pdfOnly"));
        }
        if (!hasPdfMagicBytes(file)) {
            throw new IllegalArgumentException(messages.get("policy.error.pdfInvalid"));
        }
    }

    /** Vérifie la signature réelle du fichier (%PDF), pas seulement son extension déclarée. */
    private boolean hasPdfMagicBytes(MultipartFile file) {
        byte[] header = new byte[4];
        try (InputStream in = file.getInputStream()) {
            int read = in.readNBytes(header, 0, header.length);
            return read >= 4 && header[0] == 0x25 && header[1] == 0x50 && header[2] == 0x44 && header[3] == 0x46;
        } catch (IOException e) {
            return false;
        }
    }
}
