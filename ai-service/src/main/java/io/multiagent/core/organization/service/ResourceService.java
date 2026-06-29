package io.multiagent.core.organization.service;

import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.organization.entity.Organization;
import io.multiagent.core.organization.entity.Resource;
import io.multiagent.core.organization.repository.OrganizationRepository;
import io.multiagent.core.organization.repository.ResourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ResourceService {

    private final ResourceRepository resourceRepository;
    private final OrganizationRepository organizationRepository;

    public List<Resource> findAll() {
        return resourceRepository.findByTenantId(TenantContext.getTenantId());
    }

    public List<Resource> findActive() {
        return resourceRepository.findByTenantIdAndActiveTrue(TenantContext.getTenantId());
    }

    public Resource findByEmail(String email) {
        return resourceRepository.findByTenantIdAndEmail(TenantContext.getTenantId(), email).orElse(null);
    }

    @Transactional
    public Resource create(Resource resource) {
        Organization tenant = organizationRepository.getReferenceById(TenantContext.getTenantId());
        resource.setTenant(tenant);
        return resourceRepository.save(resource);
    }

    @Transactional
    public Resource update(UUID id, Resource updates) {
        Resource existing = resourceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Resource not found: " + id));
        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getEmail() != null) existing.setEmail(updates.getEmail());
        if (updates.getType() != null) existing.setType(updates.getType());
        if (updates.getTjm() != null) existing.setTjm(updates.getTjm());
        if (updates.getActive() != null) existing.setActive(updates.getActive());
        if (updates.getResourceCompany() != null) existing.setResourceCompany(updates.getResourceCompany());
        return resourceRepository.save(existing);
    }
}
