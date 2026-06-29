CREATE TABLE consultant_assignment (
    id                    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id             UUID NOT NULL REFERENCES organization(id),
    consultant_profile_id UUID NOT NULL REFERENCES consultant_profile(id) ON DELETE CASCADE,
    project_id            UUID NOT NULL REFERENCES project(id)             ON DELETE CASCADE,
    client_id             UUID NOT NULL REFERENCES client(id)              ON DELETE CASCADE,
    tjm                   NUMERIC(10,2),
    created_at            TIMESTAMP DEFAULT NOW(),
    UNIQUE(consultant_profile_id, project_id)
);
