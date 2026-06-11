package io.multiagent.core.organization.controller;

import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.organization.entity.Organization;
import io.multiagent.core.organization.entity.ProjectEntity;
import io.multiagent.core.organization.repository.OrganizationRepository;
import io.multiagent.core.organization.repository.ProjectRepository;
import io.multiagent.core.settings.entity.ConsultantProfileEntity;
import io.multiagent.core.settings.repository.ConsultantProfileJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/projects")
public class ProjectController {

    private final ProjectRepository projectRepository;
    private final OrganizationRepository organizationRepository;
    private final ConsultantProfileJpaRepository consultantProfileRepository;

    @GetMapping
    public List<ProjectEntity> list() {
        return projectRepository.findByTenantId(TenantContext.getTenantId());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectEntity> get(@PathVariable UUID id) {
        return projectRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<ProjectEntity> create(@RequestBody ProjectEntity project) {
        Organization tenant = organizationRepository.getReferenceById(TenantContext.getTenantId());
        project.setTenant(tenant);
        return ResponseEntity.ok(projectRepository.save(project));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<ProjectEntity> update(@PathVariable UUID id, @RequestBody ProjectEntity updates) {
        ProjectEntity existing = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + id));
        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
        return ResponseEntity.ok(projectRepository.save(existing));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Map<String, String>> delete(@PathVariable UUID id) {
        projectRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "Projet supprimé"));
    }

    /** Assigner un ou plusieurs projets à un consultant */
    @PostMapping("/{projectId}/consultants/{consultantId}")
    @Transactional
    public ResponseEntity<Map<String, String>> assignConsultant(
            @PathVariable UUID projectId,
            @PathVariable UUID consultantId) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        ConsultantProfileEntity consultant = consultantProfileRepository.findById(consultantId)
                .orElseThrow(() -> new IllegalArgumentException("Consultant not found: " + consultantId));
        if (!consultant.getProjects().contains(project)) {
            consultant.getProjects().add(project);
            consultantProfileRepository.save(consultant);
        }
        return ResponseEntity.ok(Map.of("message", "Consultant assigné au projet"));
    }

    /** Retirer un consultant d'un projet */
    @DeleteMapping("/{projectId}/consultants/{consultantId}")
    @Transactional
    public ResponseEntity<Map<String, String>> removeConsultant(
            @PathVariable UUID projectId,
            @PathVariable UUID consultantId) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        ConsultantProfileEntity consultant = consultantProfileRepository.findById(consultantId)
                .orElseThrow(() -> new IllegalArgumentException("Consultant not found: " + consultantId));
        consultant.getProjects().remove(project);
        consultantProfileRepository.save(consultant);
        return ResponseEntity.ok(Map.of("message", "Consultant retiré du projet"));
    }

    /** Lister les projets d'un consultant par UUID */
    @GetMapping("/by-consultant/{consultantId}")
    @Transactional(readOnly = true)
    public ResponseEntity<List<ProjectEntity>> getByConsultant(@PathVariable UUID consultantId) {
        ConsultantProfileEntity consultant = consultantProfileRepository.findById(consultantId)
                .orElseThrow(() -> new IllegalArgumentException("Consultant not found: " + consultantId));
        return ResponseEntity.ok(List.copyOf(consultant.getProjects()));
    }

    /** Lister les projets d'un consultant par email (utilisé par la console consultant) */
    @GetMapping("/by-email")
    @Transactional(readOnly = true)
    public ResponseEntity<List<ProjectEntity>> getByEmail(@RequestParam String email) {
        UUID tenantId = TenantContext.getTenantId();
        return consultantProfileRepository.findByTenantIdAndEmailIgnoreCase(tenantId, email)
                .map(c -> ResponseEntity.ok(List.copyOf(c.getProjects())))
                .orElse(ResponseEntity.ok(List.of()));
    }
}
