-- V10 : délai de paiement par trio consultant+client (en jours)
ALTER TABLE consultant_assignment ADD COLUMN IF NOT EXISTS payment_terms_days INTEGER DEFAULT 30;
