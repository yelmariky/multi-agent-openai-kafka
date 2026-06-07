package io.multiagent.core.organization.service;

import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.organization.entity.Organization;
import io.multiagent.core.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository repository;

    public Organization getCurrentTenant() {
        return repository.findById(TenantContext.getTenantId())
                .orElseThrow(() -> new IllegalStateException("Tenant not found: " + TenantContext.getTenantId()));
    }

    public String getCurrentTenantName() {
        return getCurrentTenant().getName();
    }

    public List<Organization> findAll() {
        return repository.findAll();
    }

    public Organization findById(UUID id) {
        return repository.findById(id).orElse(null);
    }

    public Organization findBySlug(String slug) {
        return repository.findBySlug(slug).orElse(null);
    }

    @Transactional
    public Organization create(Organization org) {
        if (repository.existsBySlug(org.getSlug())) {
            throw new IllegalArgumentException("Slug already exists: " + org.getSlug());
        }
        return repository.save(org);
    }

    @Transactional
    public Organization update(UUID id, Organization updates) {
        Organization org = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + id));
        if (updates.getName() != null) org.setName(updates.getName());
        if (updates.getPlan() != null) org.setPlan(updates.getPlan());
        if (updates.getActive() != null) org.setActive(updates.getActive());
        return repository.save(org);
    }
}
