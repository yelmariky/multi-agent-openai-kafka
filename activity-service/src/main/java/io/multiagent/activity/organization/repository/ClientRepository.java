package io.multiagent.activity.organization.repository;

import io.multiagent.activity.organization.entity.Client;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ClientRepository extends JpaRepository<Client, UUID> {

    List<Client> findByTenantId(UUID tenantId);

    List<Client> findByTenantIdAndActiveTrue(UUID tenantId);
}
