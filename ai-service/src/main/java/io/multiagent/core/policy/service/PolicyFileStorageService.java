package io.multiagent.core.policy.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Conserve le fichier PDF original d'une politique importée en PDF (téléchargement fidèle) —
 * même pattern que {@code ReceiptStorageService}, répertoire dédié puisque ce sont des documents
 * de nature différente (politique tenant vs justificatif de dépense individuel).
 */
@Service
@Slf4j
public class PolicyFileStorageService {

    @Value("${ai-core.policy.storage-path:/tmp/policies}")
    private String storagePath;

    public String save(MultipartFile file, String policyDocumentId) {
        try {
            Path dir = Paths.get(storagePath);
            Files.createDirectories(dir);
            Path target = dir.resolve(policyDocumentId + ".pdf");
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("📥 [POLICY] PDF original conservé : {} ({} octets)", target, file.getSize());
            return target.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to store the policy PDF", e);
        }
    }

    public byte[] read(String path) {
        try {
            return Files.readAllBytes(Paths.get(path));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read the policy PDF: " + path, e);
        }
    }
}
