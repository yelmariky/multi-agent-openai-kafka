package io.multiagent.core.organization.repository;

import io.multiagent.core.organization.entity.Client;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClientRepository extends JpaRepository<Client, UUID> {
    List<Client> findByTenantId(UUID tenantId);
    List<Client> findByTenantIdAndActiveTrue(UUID tenantId);
    Optional<Client> findByTenantIdAndName(UUID tenantId, String name);
}
