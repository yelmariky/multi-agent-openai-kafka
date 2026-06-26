-- Demandes de congés (CP / RTT / Maladie / Formation / Autre)
CREATE TABLE IF NOT EXISTS leave_request (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL,
    consultant_email  VARCHAR(200) NOT NULL,
    type              VARCHAR(30)  NOT NULL,   -- CP | RTT | MALADIE | FORMATION | AUTRE
    start_date        DATE         NOT NULL,
    end_date          DATE         NOT NULL,
    days_count        NUMERIC(5,1) NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'DEMANDEE',  -- DEMANDEE | APPROUVEE | REFUSEE
    reason            TEXT,
    refused_reason    TEXT,
    approved_by       VARCHAR(200),
    approved_at       TIMESTAMP WITH TIME ZONE,
    created_at        TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_leave_request_tenant    ON leave_request(tenant_id);
CREATE INDEX IF NOT EXISTS idx_leave_request_consultant ON leave_request(tenant_id, consultant_email);
CREATE INDEX IF NOT EXISTS idx_leave_request_status    ON leave_request(tenant_id, status);

-- Solde CP/RTT par consultant et par année
CREATE TABLE IF NOT EXISTS leave_balance (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID         NOT NULL,
    consultant_email VARCHAR(200) NOT NULL,
    year             INTEGER      NOT NULL,
    cp_initial       NUMERIC(5,1) DEFAULT 25.0,
    cp_taken         NUMERIC(5,1) DEFAULT 0.0,
    rtt_initial      NUMERIC(5,1) DEFAULT 12.0,
    rtt_taken        NUMERIC(5,1) DEFAULT 0.0,
    UNIQUE(tenant_id, consultant_email, year)
);

CREATE INDEX IF NOT EXISTS idx_leave_balance_consultant ON leave_balance(tenant_id, consultant_email);
