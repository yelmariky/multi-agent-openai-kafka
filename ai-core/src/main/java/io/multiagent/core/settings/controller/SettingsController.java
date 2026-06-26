package io.multiagent.core.settings.controller;

import io.multiagent.core.model.SellerProfile;
import io.multiagent.core.service.WeaviateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/settings")
public class SettingsController {

    private final WeaviateService weaviateService;

    public SettingsController(WeaviateService weaviateService) {
        this.weaviateService = weaviateService;
    }

    /**
     * Returns the SellerProfile stored in Weaviate for the given company name.
     * Returns an empty profile (not 404) when none exists yet, so the frontend
     * can display a pre-filled form.
     */
    @GetMapping("/seller")
    public ResponseEntity<SellerProfile> getSeller(
            @RequestParam(required = false, defaultValue = "IA-INSIGHT") String company) {
        SellerProfile profile = weaviateService.findSellerProfile(company);
        if (profile == null) {
            profile = new SellerProfile(company, "", "", "", "", "", "", "");
        }
        return ResponseEntity.ok(profile);
    }

    /**
     * Creates or replaces the SellerProfile in Weaviate.
     */
    @PostMapping("/seller")
    public ResponseEntity<Object> saveSeller(@RequestBody SellerProfile profile) {
        try {
            weaviateService.upsertSellerProfile(profile);
            return ResponseEntity.ok(Map.of(
                    "message", "Profil vendeur sauvegardé",
                    "company", profile.companyName() == null ? "" : profile.companyName()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Erreur lors de la sauvegarde : " + e.getMessage());
        }
    }

    /**
     * Re-génère et persiste les embeddings pgvector pour tous les chunks
     * dont la colonne embedding est NULL (migration Weaviate → pgvector).
     * Réservé à l'admin — appel OpenAI par chunk.
     */
    @PostMapping("/reindex-chunks")
    public ResponseEntity<Object> reindexChunks() {
        try {
            int count = weaviateService.reindexOrphanChunks();
            return ResponseEntity.ok(Map.of("reindexed", count));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Erreur reindex : " + e.getMessage());
        }
    }
}
