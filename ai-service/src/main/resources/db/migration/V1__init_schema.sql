-- =============================================
-- V1 : Schema initial multi-tenant IA-INSIGHT
-- pgvector remplace Weaviate pour le stockage
-- vectoriel + relationnel dans une seule base.
-- =============================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "vector";

-- =============================================
-- ORGANIZATION (tenant = societe souscriptrice)
-- =============================================
CREATE TABLE organization (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(100) NOT NULL UNIQUE,
    plan            VARCHAR(50)  DEFAULT 'STARTER',
    active          BOOLEAN      DEFAULT TRUE,
    keycloak_realm  VARCHAR(100) NOT NULL UNIQUE,
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW()
);

-- =============================================
-- RESOURCE (salarie, freelance, sous-traitant)
-- =============================================
CREATE TYPE resource_type AS ENUM ('SALARIE', 'FREELANCE', 'SOUS_TRAITANT');

CREATE TABLE resource (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id        UUID         NOT NULL REFERENCES organization(id),
    email            VARCHAR(255) NOT NULL,
    name             VARCHAR(255) NOT NULL,
    type             resource_type NOT NULL DEFAULT 'SALARIE',
    tjm              NUMERIC(10,2),
    active           BOOLEAN      DEFAULT TRUE,
    resource_company VARCHAR(255),
    keycloak_user_id VARCHAR(255),
    created_at       TIMESTAMP    DEFAULT NOW(),
    UNIQUE(tenant_id, email)
);

-- =============================================
-- CLIENT (entreprise cliente du tenant)
-- =============================================
CREATE TABLE client (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID         NOT NULL REFERENCES organization(id),
    name            VARCHAR(255) NOT NULL,
    address         TEXT,
    rcs             VARCHAR(100),
    contact_name    VARCHAR(255),
    contact_email   VARCHAR(255),
    created_at      TIMESTAMP    DEFAULT NOW(),
    UNIQUE(tenant_id, name)
);

-- =============================================
-- MISSION (affectation ressource chez un client)
-- =============================================
CREATE TABLE mission (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID         NOT NULL REFERENCES organization(id),
    resource_id     UUID         NOT NULL REFERENCES resource(id),
    client_id       UUID         NOT NULL REFERENCES client(id),
    title           VARCHAR(255) NOT NULL,
    status          VARCHAR(50)  DEFAULT 'ACTIVE',
    start_date      DATE         NOT NULL,
    end_date        DATE,
    tjm_factured    NUMERIC(10,2) NOT NULL,
    tjm_cost        NUMERIC(10,2),
    billing_day     INTEGER      DEFAULT 1,
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW()
);

-- =============================================
-- SELLER PROFILE (profil emetteur/vendeur)
-- =============================================
CREATE TABLE seller_profile (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id           UUID         NOT NULL REFERENCES organization(id),
    company_name        VARCHAR(255) NOT NULL,
    address             TEXT,
    rcs                 VARCHAR(100),
    iban                VARCHAR(50),
    bic                 VARCHAR(20),
    email               VARCHAR(255),
    capital             VARCHAR(100),
    late_payment_clause TEXT,
    UNIQUE(tenant_id)
);

-- =============================================
-- CONSULTANT PROFILE
-- =============================================
CREATE TABLE consultant_profile (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID         NOT NULL REFERENCES organization(id),
    email           VARCHAR(255) NOT NULL,
    name            VARCHAR(255),
    role            VARCHAR(100),
    company         VARCHAR(255),
    client_name     VARCHAR(255),
    client_address  TEXT,
    client_rcs      VARCHAR(100),
    tjm             NUMERIC(10,2),
    active          BOOLEAN      DEFAULT TRUE,
    UNIQUE(tenant_id, email)
);

-- =============================================
-- EXPENSE (note de frais)
-- =============================================
CREATE TABLE expense (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id           UUID         NOT NULL REFERENCES organization(id),
    expense_id          INTEGER,
    consultant_email    VARCHAR(255),
    amount              NUMERIC(12,2),
    currency            VARCHAR(10)  DEFAULT 'EUR',
    type                VARCHAR(100),
    km                  NUMERIC(10,2),
    expense_date        DATE,
    date_text           VARCHAR(50),
    description         TEXT,
    original_text       TEXT,
    source              VARCHAR(50),
    payment_mode        VARCHAR(50),
    address             TEXT,
    company             VARCHAR(255),
    duplicate_flag      BOOLEAN      DEFAULT FALSE,
    hash                VARCHAR(255),
    approval_status     VARCHAR(50)  DEFAULT 'PENDING',
    approved_by         VARCHAR(255),
    approved_at         TIMESTAMP,
    approval_note       TEXT,
    receipt_path        VARCHAR(500),
    absence_periods_json TEXT,
    source_text         TEXT,
    embedding           vector(2000),
    created_at          TIMESTAMP    DEFAULT NOW()
);

-- =============================================
-- CRA (compte-rendu d'activite)
-- =============================================
CREATE TABLE cra (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID         NOT NULL REFERENCES organization(id),
    consultant      VARCHAR(255) NOT NULL,
    company         VARCHAR(255),
    client_company  VARCHAR(255),
    billing_month   VARCHAR(7)   NOT NULL,
    entries_json    TEXT,
    total_days      NUMERIC(5,1),
    status          VARCHAR(50)  DEFAULT 'BROUILLON',
    submitted_at    VARCHAR(50),
    validated_at    VARCHAR(50),
    validated_by    VARCHAR(255),
    refused_reason  TEXT,
    mission_id      UUID         REFERENCES mission(id),
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW()
);

-- =============================================
-- INVOICE (facture)
-- =============================================
CREATE TABLE invoice (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id           UUID         NOT NULL REFERENCES organization(id),
    invoice_name        VARCHAR(100),
    invoice_date        DATE,
    billing_month       VARCHAR(7),
    seller_company_name VARCHAR(255),
    seller_address      TEXT,
    seller_rcs          VARCHAR(100),
    client_company_name VARCHAR(255),
    client_address      TEXT,
    client_rcs          VARCHAR(100),
    invoice_title       VARCHAR(255),
    days_count          INTEGER,
    days_exact          NUMERIC(5,1),
    unit_price_ht       NUMERIC(12,2),
    total_ht            NUMERIC(12,2),
    vat_rate            NUMERIC(5,2),
    total_ttc           NUMERIC(12,2),
    currency            VARCHAR(10)  DEFAULT 'EUR',
    payment_due_date    DATE,
    late_payment_clause TEXT,
    notes               TEXT,
    consultant_email    VARCHAR(255),
    mission_id          UUID         REFERENCES mission(id),
    source_text         TEXT,
    pdf_path            VARCHAR(500),
    excel_path          VARCHAR(500),
    embedding           vector(2000),
    created_at          TIMESTAMP    DEFAULT NOW()
);

-- =============================================
-- DOCUMENT CHUNK (RAG)
-- =============================================
CREATE TABLE document_chunk (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID         NOT NULL REFERENCES organization(id),
    source          VARCHAR(500),
    chunk_index     INTEGER,
    content         TEXT,
    embedding       vector(2000),
    created_at      TIMESTAMP    DEFAULT NOW()
);

-- =============================================
-- NOTIFICATION (admin + consultant, persistees)
-- =============================================
CREATE TABLE notification (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID         NOT NULL REFERENCES organization(id),
    target_type     VARCHAR(20)  NOT NULL,
    target          VARCHAR(255) NOT NULL,
    title           VARCHAR(500),
    message         TEXT,
    read            BOOLEAN      DEFAULT FALSE,
    created_at      TIMESTAMP    DEFAULT NOW()
);

-- =============================================
-- INDEX relationnels
-- =============================================
CREATE INDEX idx_resource_tenant       ON resource(tenant_id);
CREATE INDEX idx_resource_email        ON resource(tenant_id, email);
CREATE INDEX idx_client_tenant         ON client(tenant_id);
CREATE INDEX idx_mission_tenant        ON mission(tenant_id);
CREATE INDEX idx_mission_resource      ON mission(resource_id);
CREATE INDEX idx_mission_status        ON mission(tenant_id, status);
CREATE INDEX idx_seller_profile_tenant ON seller_profile(tenant_id);
CREATE INDEX idx_consultant_tenant     ON consultant_profile(tenant_id);
CREATE INDEX idx_expense_tenant        ON expense(tenant_id);
CREATE INDEX idx_expense_date          ON expense(tenant_id, expense_date);
CREATE INDEX idx_expense_consultant    ON expense(tenant_id, consultant_email);
CREATE INDEX idx_expense_status        ON expense(tenant_id, approval_status);
CREATE INDEX idx_cra_tenant            ON cra(tenant_id);
CREATE INDEX idx_cra_month             ON cra(tenant_id, billing_month);
CREATE INDEX idx_cra_consultant        ON cra(tenant_id, consultant);
CREATE INDEX idx_invoice_tenant        ON invoice(tenant_id);
CREATE INDEX idx_invoice_billing       ON invoice(tenant_id, billing_month);
CREATE INDEX idx_document_chunk_tenant ON document_chunk(tenant_id);
CREATE INDEX idx_notification_tenant   ON notification(tenant_id);
CREATE INDEX idx_notification_target   ON notification(tenant_id, target_type, target);

-- =============================================
-- Index HNSW pour recherche vectorielle (cosine)
-- =============================================
CREATE INDEX idx_expense_embedding        ON expense        USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_invoice_embedding        ON invoice        USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_document_chunk_embedding ON document_chunk USING hnsw (embedding vector_cosine_ops);

-- =============================================
-- Seed : tenant IA-INSIGHT (premier tenant)
-- =============================================
INSERT INTO organization (id, name, slug, plan, keycloak_realm)
VALUES ('550e8400-e29b-41d4-a716-446655440000', 'IA-INSIGHT SAS', 'ia-insight', 'PRO', 'ia-insight');
