package io.multiagent.core.policy.service;

import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.policy.entity.PolicyDocumentEntity;
import io.multiagent.core.policy.repository.PolicyDocumentJpaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Gestion des versions de politique tenant (ex: politique de remboursement de notes de frais).
 *
 * <p>Pas de chunking/embedding pgvector : une politique tient toujours en quelques pages, donc
 * son texte complet est injecté tel quel dans le prompt de conformité (voir
 * {@code RAGService.evaluatePolicyCompliance}) — un retrieval par similarité n'apporterait aucun
 * bénéfice et risquerait de manquer la clause pertinente pour rien.</p>
 */
@Slf4j
@Service
public class PolicyIngestionService {

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_ARCHIVED = "ARCHIVED";

    private final PolicyDocumentJpaRepository policyRepo;
    private final PolicyFileStorageService fileStorage;
    private final io.multiagent.core.i18n.Messages messages;

    public PolicyIngestionService(PolicyDocumentJpaRepository policyRepo, PolicyFileStorageService fileStorage,
                                  io.multiagent.core.i18n.Messages messages) {
        this.messages = messages;
        this.policyRepo = policyRepo;
        this.fileStorage = fileStorage;
    }

    /** Crée une nouvelle version DRAFT (texte libre) — ne modifie jamais la version ACTIVE existante. */
    @Transactional
    public PolicyDocumentEntity ingest(String policyType, String title, String rawText, String createdBy) {
        return ingest(policyType, title, rawText, createdBy, null);
    }

    /**
     * Crée une nouvelle version DRAFT importée depuis un PDF — conserve le fichier original pour
     * un téléchargement fidèle (voir {@link PolicyFileStorageService}), en plus du texte extrait
     * utilisé pour l'évaluation de conformité.
     */
    @Transactional
    public PolicyDocumentEntity ingestPdf(String policyType, String title, String rawText, String createdBy,
            MultipartFile originalFile) {
        PolicyDocumentEntity policy = ingest(policyType, title, rawText, createdBy, null);
        String path = fileStorage.save(originalFile, policy.getId().toString());
        policy.setSourceFilePath(path);
        return policyRepo.save(policy);
    }

    private PolicyDocumentEntity ingest(String policyType, String title, String rawText, String createdBy,
            String sourceFilePath) {
        UUID tenantId = TenantContext.getTenantId();
        int nextVersion = policyRepo.findTopByTenantIdAndPolicyTypeOrderByVersionDesc(tenantId, policyType)
                .map(p -> p.getVersion() + 1)
                .orElse(1);

        PolicyDocumentEntity policy = new PolicyDocumentEntity();
        policy.setTenantId(tenantId);
        policy.setPolicyType(policyType);
        policy.setTitle(title);
        policy.setVersion(nextVersion);
        policy.setStatus(STATUS_DRAFT);
        policy.setRawText(rawText);
        policy.setCreatedBy(createdBy);
        policy.setSourceFilePath(sourceFilePath);
        policy = policyRepo.save(policy);

        log.info("🗂️ [POLICY] version={} type={} tenant={} — créée en DRAFT", nextVersion, policyType, tenantId);
        return policy;
    }

    /** Active une version DRAFT et archive l'ancienne version ACTIVE (une seule ACTIVE à la fois). */
    @Transactional
    public PolicyDocumentEntity activate(UUID policyDocumentId) {
        UUID tenantId = TenantContext.getTenantId();
        PolicyDocumentEntity target = policyRepo.findByIdAndTenantId(policyDocumentId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException(messages.get("policy.error.notFound", String.valueOf(policyDocumentId))));
        if (!STATUS_DRAFT.equals(target.getStatus())) {
            throw new IllegalStateException(
                    messages.get("policy.error.onlyDraftActivatable", String.valueOf(target.getStatus())));
        }
        // saveAndFlush (pas save) : l'index unique partiel n'autorise qu'une seule ligne ACTIVE par
        // (tenant_id, policy_type) — sans flush immédiat, Hibernate peut écrire l'UPDATE de "target"
        // (nouvelle ACTIVE) avant celui de "previous" (ARCHIVED), créant un instant avec deux lignes
        // ACTIVE et violant la contrainte.
        policyRepo.findByTenantIdAndPolicyTypeAndStatus(tenantId, target.getPolicyType(), STATUS_ACTIVE)
                .ifPresent(previous -> {
                    previous.setStatus(STATUS_ARCHIVED);
                    policyRepo.saveAndFlush(previous);
                });
        target.setStatus(STATUS_ACTIVE);
        target.setActivatedAt(Instant.now());
        policyRepo.save(target);
        log.info("🗂️ [POLICY] version={} type={} tenant={} — activée", target.getVersion(), target.getPolicyType(), tenantId);
        return target;
    }

    public Optional<PolicyDocumentEntity> findById(UUID id) {
        return policyRepo.findByIdAndTenantId(id, TenantContext.getTenantId());
    }

    public Optional<PolicyDocumentEntity> findActivePolicy(String policyType) {
        UUID tenantId = TenantContext.getTenantId();
        return policyRepo.findByTenantIdAndPolicyTypeAndStatus(tenantId, policyType, STATUS_ACTIVE);
    }

    /** Historique complet des versions (toutes statuts), la plus récente en premier. */
    public List<PolicyDocumentEntity> listVersions(String policyType) {
        UUID tenantId = TenantContext.getTenantId();
        return policyRepo.findByTenantIdAndPolicyTypeOrderByVersionDesc(tenantId, policyType);
    }
}
