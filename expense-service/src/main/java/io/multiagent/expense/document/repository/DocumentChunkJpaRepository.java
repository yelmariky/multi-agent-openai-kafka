package io.multiagent.expense.document.repository;

import io.multiagent.expense.document.entity.DocumentChunkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DocumentChunkJpaRepository extends JpaRepository<DocumentChunkEntity, UUID> {

    List<DocumentChunkEntity> findByTenantId(UUID tenantId);

    List<DocumentChunkEntity> findByTenantIdAndSource(UUID tenantId, String source);

    void deleteByTenantIdAndSource(UUID tenantId, String source);

    @Query(value = "SELECT * FROM document_chunk WHERE tenant_id = :tenantId " +
            "ORDER BY embedding <=> CAST(:queryVector AS vector) LIMIT :limit",
            nativeQuery = true)
    List<DocumentChunkEntity> findSimilar(@Param("tenantId") UUID tenantId,
                                         @Param("queryVector") String queryVector,
                                         @Param("limit") int limit);

    /**
     * Recherche vectorielle avec seuil de similarité cosine minimum.
     * Distance cosine = 1 - similarité → on filtre sur distance <= (1 - minSimilarity).
     * Protège contre les embeddings adversariaux (OWASP LLM08).
     */
    @Query(value = "SELECT * FROM document_chunk " +
            "WHERE tenant_id = :tenantId " +
            "AND (embedding <=> CAST(:queryVector AS vector)) <= (1.0 - :minSimilarity) " +
            "ORDER BY embedding <=> CAST(:queryVector AS vector) LIMIT :limit",
            nativeQuery = true)
    List<DocumentChunkEntity> findSimilarAboveThreshold(@Param("tenantId") UUID tenantId,
                                                        @Param("queryVector") String queryVector,
                                                        @Param("limit") int limit,
                                                        @Param("minSimilarity") double minSimilarity);

    @Modifying
    @Query(value = "UPDATE document_chunk SET embedding = CAST(:vector AS vector) WHERE id = :id",
            nativeQuery = true)
    void updateEmbedding(@Param("id") UUID id, @Param("vector") String vector);

    @Query(value = "SELECT * FROM document_chunk WHERE tenant_id = :tenantId AND embedding IS NULL",
            nativeQuery = true)
    List<DocumentChunkEntity> findOrphans(@Param("tenantId") UUID tenantId);
}
