package io.multiagent.core.settings.controller;

import io.multiagent.core.model.ConsultantProfile;
import io.multiagent.core.service.WeaviateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/consultants")
public class ConsultantController {

    private final WeaviateService weaviateService;

    @GetMapping("/profiles")
    public ResponseEntity<List<ConsultantProfile>> getProfiles(
            @RequestParam(required = false, defaultValue = "IA-INSIGHT") String company) {
        return ResponseEntity.ok(weaviateService.findAllConsultantProfiles(company));
    }

    @PostMapping("/profiles")
    public ResponseEntity<Object> upsertProfile(@RequestBody ConsultantProfile profile) {
        try {
            weaviateService.upsertConsultantProfile(profile);
            return ResponseEntity.ok(Map.of(
                    "message", "Profil consultant sauvegardé",
                    "email", profile.email() == null ? "" : profile.email()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Erreur : " + e.getMessage());
        }
    }

}
