-- =============================================
-- V2 : Projets et association consultant-projet
-- =============================================

CREATE TABLE project (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id   UUID         NOT NULL REFERENCES organization(id),
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    created_at  TIMESTAMP    DEFAULT NOW(),
    UNIQUE(tenant_id, name)
);

CREATE TABLE consultant_project (
    consultant_profile_id UUID NOT NULL REFERENCES consultant_profile(id) ON DELETE CASCADE,
    project_id            UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    PRIMARY KEY (consultant_profile_id, project_id)
);

ALTER TABLE cra ADD COLUMN project_id UUID REFERENCES project(id);

CREATE INDEX idx_project_tenant ON project(tenant_id);
CREATE INDEX idx_cra_project    ON cra(project_id);
