-- Abonnement SaaS par organisation cliente (facturation IA-INSIGHT → tenant).
-- Le nombre de consultants n'est PAS stocké : il est compté en temps réel dans
-- consultant_profile (is_consultant = TRUE AND active = TRUE) au moment de facturer.
CREATE TABLE IF NOT EXISTS tenant_subscription (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id             UUID NOT NULL UNIQUE REFERENCES organization(id),
    offer                       VARCHAR(20) NOT NULL,                    -- ESSENTIEL | CROISSANCE | ENTERPRISE
    billing_period              VARCHAR(20) NOT NULL DEFAULT 'ANNUEL',   -- MENSUEL | TRIMESTRIEL | ANNUEL
    negotiated_monthly_price_ht NUMERIC(10,2),                           -- obligatoire si ENTERPRISE
    client_address              TEXT,
    client_rcs                  VARCHAR(120),
    start_date                  DATE NOT NULL,
    next_invoice_date           DATE,                                    -- NULL = facturation auto suspendue
    active                      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_tenant_subscription_due
    ON tenant_subscription (next_invoice_date) WHERE active = TRUE;
