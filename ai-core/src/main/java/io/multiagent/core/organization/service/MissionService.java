package io.multiagent.core.organization.service;

import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.organization.entity.Mission;
import io.multiagent.core.organization.entity.Organization;
import io.multiagent.core.organization.repository.ClientRepository;
import io.multiagent.core.organization.repository.MissionRepository;
import io.multiagent.core.organization.repository.OrganizationRepository;
import io.multiagent.core.organization.repository.ResourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MissionService {

    private final MissionRepository missionRepository;
    private final ResourceRepository resourceRepository;
    private final ClientRepository clientRepository;
    private final OrganizationRepository organizationRepository;

    public List<Mission> findAll() {
        return missionRepository.findByTenantId(TenantContext.getTenantId());
    }

    public List<Mission> findByStatus(String status) {
        return missionRepository.findByTenantIdAndStatus(TenantContext.getTenantId(), status);
    }

    public List<Mission> findByResource(UUID resourceId) {
        return missionRepository.findByTenantIdAndResourceId(TenantContext.getTenantId(), resourceId);
    }

    public Mission findById(UUID id) {
        return missionRepository.findById(id).orElse(null);
    }

    @Transactional
    public Mission create(Mission mission) {
        UUID tenantId = TenantContext.getTenantId();
        Organization tenant = organizationRepository.getReferenceById(tenantId);
        mission.setTenant(tenant);
        mission.setResource(resourceRepository.getReferenceById(mission.getResource().getId()));
        mission.setClient(clientRepository.getReferenceById(mission.getClient().getId()));
        return missionRepository.save(mission);
    }

    @Transactional
    public Mission update(UUID id, Mission updates) {
        Mission existing = missionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Mission not found: " + id));
        if (updates.getTitle() != null) existing.setTitle(updates.getTitle());
        if (updates.getStatus() != null) existing.setStatus(updates.getStatus());
        if (updates.getStartDate() != null) existing.setStartDate(updates.getStartDate());
        if (updates.getEndDate() != null) existing.setEndDate(updates.getEndDate());
        if (updates.getTjmFactured() != null) existing.setTjmFactured(updates.getTjmFactured());
        if (updates.getTjmCost() != null) existing.setTjmCost(updates.getTjmCost());
        return missionRepository.save(existing);
    }

    /**
     * Returns active missions for a resource identified by email.
     */
    @Transactional(readOnly = true)
    public List<Mission> findByResourceEmail(String email) {
        UUID tenantId = TenantContext.getTenantId();
        return resourceRepository.findByTenantIdAndEmail(tenantId, email)
                .map(resource -> missionRepository.findByTenantIdAndResourceId(tenantId, resource.getId()))
                .orElse(List.of());
    }

    @Transactional
    public void delete(UUID id) {
        missionRepository.deleteById(id);
    }
}
