-- Gouvernance IA : colonnes de supervision sur la table expense
ALTER TABLE expense
    ADD COLUMN IF NOT EXISTS ai_confidence_score  NUMERIC(4,3),
    ADD COLUMN IF NOT EXISTS ai_flags             TEXT,
    ADD COLUMN IF NOT EXISTS ai_review_required   BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN expense.ai_confidence_score IS 'Score de confiance de l extraction LLM (0.0 à 1.0)';
COMMENT ON COLUMN expense.ai_flags            IS 'JSON des signaux détectés (MONTANT_ELEVE, DATE_FUTURE…)';
COMMENT ON COLUMN expense.ai_review_required  IS 'TRUE si un guardrail a détecté un signal nécessitant une revue humaine';
