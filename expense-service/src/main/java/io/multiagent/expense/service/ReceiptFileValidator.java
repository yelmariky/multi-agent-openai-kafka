package io.multiagent.expense.service;

import io.multiagent.expense.exception.InvalidReceiptFileException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;

/**
 * Valide les justificatifs uploadés avant tout traitement OCR : seuls PDF/JPEG/PNG sont
 * acceptés, la taille est plafonnée, et le contenu réel (magic bytes) doit correspondre à
 * l'extension déclarée — empêche un binaire/exe renommé en .pdf ou .jpg d'être accepté.
 */
@Service
@Slf4j
public class ReceiptFileValidator {

    // Aligné avec spring.servlet.multipart.max-file-size (application.yml) — les deux doivent rester synchronisés.
    private static final long MAX_SIZE_BYTES = 8L * 1024 * 1024;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "jpg", "jpeg", "png");

    public void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidReceiptFileException("Fichier manquant ou vide.");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new InvalidReceiptFileException("Fichier trop volumineux (8 Mo maximum).");
        }

        String extension = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new InvalidReceiptFileException("Format non autorisé — seuls les fichiers PDF, JPG et PNG sont acceptés.");
        }

        String sniffed = sniffContentType(file);
        boolean matches = switch (sniffed == null ? "" : sniffed) {
            case "application/pdf" -> extension.equals("pdf");
            case "image/jpeg" -> extension.equals("jpg") || extension.equals("jpeg");
            case "image/png" -> extension.equals("png");
            default -> false;
        };
        if (!matches) {
            log.warn("🛡️ [RECEIPT] Fichier rejeté — contenu réel ({}) ne correspond pas à l'extension déclarée ({})",
                    sniffed, extension);
            throw new InvalidReceiptFileException(
                    "Le contenu du fichier ne correspond pas à son extension — fichier corrompu ou renommé.");
        }
    }

    private String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Détecte le vrai type du fichier à partir de ses premiers octets (magic bytes), pas de son extension/Content-Type déclaré. */
    private String sniffContentType(MultipartFile file) {
        byte[] header = new byte[8];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.readNBytes(header, 0, header.length);
        } catch (IOException e) {
            throw new InvalidReceiptFileException("Impossible de lire le fichier uploadé.");
        }
        if (read >= 4 && header[0] == 0x25 && header[1] == 0x50 && header[2] == 0x44 && header[3] == 0x46) {
            return "application/pdf"; // %PDF
        }
        if (read >= 3 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (read >= 8 && (header[0] & 0xFF) == 0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47
                && header[4] == 0x0D && header[5] == 0x0A && header[6] == 0x1A && header[7] == 0x0A) {
            return "image/png";
        }
        return null;
    }
}
