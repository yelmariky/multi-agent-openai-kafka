-- Conserve le fichier PDF original pour les politiques importées en PDF (téléchargement fidèle).
-- NULL pour les politiques saisies en texte libre — un PDF est alors régénéré à la volée depuis
-- raw_text au moment du téléchargement (voir PolicyPdfRenderingService).
ALTER TABLE policy_document ADD COLUMN source_file_path VARCHAR(500);
