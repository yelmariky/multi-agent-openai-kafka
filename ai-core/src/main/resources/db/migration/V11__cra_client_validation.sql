-- Champs de validation client sur le CRA (référence retour client + date)
ALTER TABLE cra ADD COLUMN IF NOT EXISTS client_validation_ref  VARCHAR(200);
ALTER TABLE cra ADD COLUMN IF NOT EXISTS client_validation_date DATE;
