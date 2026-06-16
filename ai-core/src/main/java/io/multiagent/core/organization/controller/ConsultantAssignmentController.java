package io.multiagent.core.organization.controller;

import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.organization.entity.Client;
import io.multiagent.core.organization.entity.ConsultantAssignmentEntity;
import io.multiagent.core.organization.entity.ProjectEntity;
import io.multiagent.core.organization.repository.ClientRepository;
import io.multiagent.core.organization.repository.ConsultantAssignmentRepository;
import io.multiagent.core.organization.repository.ProjectRepository;
import io.multiagent.core.settings.entity.ConsultantProfileEntity;
import io.multiagent.core.settings.repository.ConsultantProfileJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/consultants/{consultantId}/assignments")
public class ConsultantAssignmentController {

    private final ConsultantAssignmentRepository assignmentRepository;
    private final ConsultantProfileJpaRepository consultantProfileRepository;
    private final ProjectRepository projectRepository;
    private final ClientRepository clientRepository;

    public record AssignmentRequest(UUID projectId, UUID clientId, BigDecimal tjm) {}

    @GetMapping
    public List<ConsultantAssignmentEntity> list(@PathVariable UUID consultantId) {
        return assignmentRepository.findByConsultantProfileIdAndTenantId(consultantId, TenantContext.getTenantId());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<ConsultantAssignmentEntity> create(
            @PathVariable UUID consultantId,
            @RequestBody AssignmentRequest req) {

        UUID tenantId = TenantContext.getTenantId();
        ConsultantProfileEntity consultant = consultantProfileRepository.findById(consultantId)
                .orElseThrow(() -> new IllegalArgumentException("Consultant not found: " + consultantId));
        ProjectEntity project = projectRepository.findById(req.projectId())
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + req.projectId()));
        Client client = clientRepository.findById(req.clientId())
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + req.clientId()));

        ConsultantAssignmentEntity assignment = new ConsultantAssignmentEntity();
        assignment.setTenantId(tenantId);
        assignment.setConsultantProfile(consultant);
        assignment.setProject(project);
        assignment.setClient(client);
        assignment.setTjm(req.tjm());

        return ResponseEntity.ok(assignmentRepository.save(assignment));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<ConsultantAssignmentEntity> update(
            @PathVariable UUID consultantId,
            @PathVariable UUID id,
            @RequestBody AssignmentRequest req) {

        UUID tenantId = TenantContext.getTenantId();
        ConsultantAssignmentEntity assignment = assignmentRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found: " + id));

        ProjectEntity project = projectRepository.findById(req.projectId())
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + req.projectId()));
        Client client = clientRepository.findById(req.clientId())
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + req.clientId()));

        assignment.setProject(project);
        assignment.setClient(client);
        assignment.setTjm(req.tjm());

        return ResponseEntity.ok(assignmentRepository.save(assignment));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable UUID consultantId, @PathVariable UUID id) {
        UUID tenantId = TenantContext.getTenantId();
        if (assignmentRepository.findByIdAndTenantId(id, tenantId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        assignmentRepository.deleteByIdAndTenantId(id, tenantId);
        return ResponseEntity.noContent().build();
    }
}
