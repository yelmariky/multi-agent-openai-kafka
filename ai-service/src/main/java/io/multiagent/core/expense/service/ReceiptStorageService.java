package io.multiagent.core.expense.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

@Service
@Slf4j
public class ReceiptStorageService {

    @Value("${ai-core.ocr.storage-path:/tmp/receipts}")
    private String storagePath;

    /** Retrouve le fichier sauvegardé par {@link #save} à partir de son id (préfixe du nom de fichier). */
    public Optional<Path> findById(String id) {
        Path dir = Paths.get(storagePath);
        if (!Files.isDirectory(dir)) return Optional.empty();
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> p.getFileName().toString().startsWith(id + "-"))
                    .max(Comparator.comparingLong(this::lastModifiedOrZero));
        } catch (IOException e) {
            log.warn("findById({}) échoué : {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    private long lastModifiedOrZero(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0L; }
    }

    public Path save(MultipartFile file, String id) {
        try {
            Path dir = Paths.get(storagePath);
            Files.createDirectories(dir);
            String safeName = sanitize(file.getOriginalFilename());
            Path target = dir.resolve(id + "-" + safeName);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("📥 Fichier reçu : {} ({} octets) → {}", safeName, file.getSize(), target);
            return target;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to store the uploaded file", e);
        }
    }

    private String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "receipt.bin";
        }
        String clean = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        return clean.length() > 120 ? clean.substring(clean.length() - 120) : clean;
    }
}
