package io.multiagent.core.model;

public record ConsultantProfile(
        String id,
        String email,
        String name,
        String role,
        String company,
        String clientName,
        String clientAddress,
        String clientRcs,
        String clientContactEmail,
        Double tjm,
        Boolean active
) {
}
