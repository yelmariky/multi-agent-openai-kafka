package io.multiagent.core.expense.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class OcrService {

    @Value("${ai-core.ocr.tesseract-command:tesseract}")
    private String tesseractCmd;

    @Value("${ai-core.ocr.pdftoppm-command:pdftoppm}")
    private String pdftoppmCmd;

    @Value("${ai-core.ocr.lang:eng+fra}")
    private String lang;

    @Value("${ai-core.ocr.dpi:300}")
    private int dpi;

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    public String extractText(Path file) {
        String lower = file.getFileName().toString().toLowerCase();
        Path imagePath = file;
        List<Path> temp = new ArrayList<>();

        try {
            if (lower.endsWith(".pdf")) {
                Path base = Files.createTempFile("receipt-ocr-", "");
                Files.deleteIfExists(base); // pdftoppm ajoute l'extension
                String baseNoExt = base.toString();
                runCommand(List.of(
                        pdftoppmCmd,
                        "-singlefile",
                        "-png",
                        file.toString(),
                        baseNoExt
                ));
                imagePath = Path.of(baseNoExt + ".png");
                temp.add(imagePath);
            }

            Path outBase = Files.createTempFile("receipt-ocr-out-", "");
            Files.deleteIfExists(outBase);
            Path txtPath = Path.of(outBase.toString() + ".txt");
            temp.add(outBase);
            temp.add(txtPath);
            runCommand(List.of(
                    tesseractCmd,
                    imagePath.toString(),
                    outBase.toString(),
                    "-l", lang,
                    "--dpi", String.valueOf(dpi),
                    "--psm", "3"
            ));

            if (!Files.exists(txtPath)) {
                throw new IllegalStateException("Tesseract produced no output");
            }

            return Files.readString(txtPath);

        } catch (Exception e) {
            throw new IllegalStateException("OCR failed: " + e.getMessage(), e);
        } finally {
            temp.forEach(this::safeDelete);
        }
    }

    /**
     * Retourne un chemin d'image compatible avec l'API Vision (png/jpeg/gif/webp).
     * Convertit un PDF en PNG si besoin — le fichier retourné doit être supprimé par
     * l'appelant s'il diffère de {@code file} (fichier temporaire de conversion).
     * Retourne {@code null} si la conversion échoue (l'appelant doit alors renoncer au fallback Vision).
     */
    public Path prepareImageForVision(Path file) {
        String lower = file.getFileName().toString().toLowerCase();
        if (!lower.endsWith(".pdf")) {
            return file; // déjà une image — format déjà validé en amont (ReceiptFileValidator)
        }
        try {
            Path base = Files.createTempFile("receipt-vision-", "");
            Files.deleteIfExists(base); // pdftoppm ajoute l'extension
            runCommand(List.of(pdftoppmCmd, "-singlefile", "-png", file.toString(), base.toString()));
            return Path.of(base + ".png");
        } catch (Exception e) {
            log.warn("⚠️ Conversion PDF→PNG pour Vision échouée : {}", e.getMessage());
            return null;
        }
    }

    private void runCommand(List<String> command) throws Exception {
        log.debug("OCR exec: {}", String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        Process p = pb.start();
        boolean finished = p.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("OCR command timeout: " + String.join(" ", command));
        }
        if (p.exitValue() != 0) {
            String err = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))
                    .lines()
                    .reduce("", (a, b) -> a + b + "\n");
            throw new IllegalStateException("OCR command failed (" + p.exitValue() + "): " + err);
        }
    }

    private void safeDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }
}
