package io.multiagent.core.organization.controller;

import io.multiagent.core.organization.entity.Mission;
import io.multiagent.core.organization.service.MissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/missions")
public class MissionController {

    private final MissionService missionService;

    @GetMapping
    public List<Mission> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID resourceId) {
        if (resourceId != null) return missionService.findByResource(resourceId);
        if (status != null) return missionService.findByStatus(status);
        return missionService.findAll();
    }

    @GetMapping("/by-email")
    public List<Mission> listByResourceEmail(@RequestParam String email) {
        return missionService.findByResourceEmail(email);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Mission> get(@PathVariable UUID id) {
        Mission m = missionService.findById(id);
        return m != null ? ResponseEntity.ok(m) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public ResponseEntity<Mission> create(@RequestBody Mission mission) {
        return ResponseEntity.ok(missionService.create(mission));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Mission> update(@PathVariable UUID id, @RequestBody Mission updates) {
        return ResponseEntity.ok(missionService.update(id, updates));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable UUID id) {
        missionService.delete(id);
        return ResponseEntity.ok(Map.of("message", "Mission supprimee"));
    }
}
