-- V9 : paramétrage du délai de paiement par client (en jours)
-- 30 = 1 mois, 45 = 45 jours, 60 = 2 mois
ALTER TABLE client ADD COLUMN IF NOT EXISTS payment_terms_days INTEGER DEFAULT 30;
