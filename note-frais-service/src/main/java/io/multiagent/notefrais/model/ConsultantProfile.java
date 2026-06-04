package io.multiagent.notefrais.model;

public record ConsultantProfile(
        String email,
        String name,
        String role,
        String company,
        String clientName,
        String clientAddress,
        String clientRcs,
        Double tjm,
        Boolean active
) {
}
