-- Gouvernance IA : table d'audit des appels LLM
CREATE TABLE llm_audit_log (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID,
    user_email      VARCHAR(255),
    feature         VARCHAR(100) NOT NULL,
    model           VARCHAR(100) NOT NULL,
    prompt_tokens   INT,
    completion_tokens INT,
    total_tokens    INT,
    input_hash      VARCHAR(64),
    response_summary TEXT,
    ai_flags        TEXT,
    confidence_score NUMERIC(4,3),
    duration_ms     INT,
    success         BOOLEAN      NOT NULL DEFAULT TRUE,
    error_message   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_llm_audit_tenant    ON llm_audit_log (tenant_id);
CREATE INDEX idx_llm_audit_feature   ON llm_audit_log (feature);
CREATE INDEX idx_llm_audit_created   ON llm_audit_log (created_at DESC);

COMMENT ON TABLE  llm_audit_log              IS 'Trace chaque appel LLM pour auditabilité et gouvernance IA';
COMMENT ON COLUMN llm_audit_log.input_hash   IS 'SHA-256 du prompt (jamais le texte brut pour respecter le RGPD)';
COMMENT ON COLUMN llm_audit_log.ai_flags     IS 'JSON : signaux détectés (montant_eleve, date_future, categorie_inconnue…)';
COMMENT ON COLUMN llm_audit_log.confidence_score IS 'Score de confiance de l extraction LLM (0.0-1.0)';
