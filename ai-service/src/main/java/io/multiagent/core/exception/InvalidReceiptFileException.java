package io.multiagent.core.exception;

/**
 * Levée quand un fichier de justificatif uploadé est rejeté : format non autorisé,
 * contenu ne correspondant pas à l'extension déclarée, ou taille excessive.
 */
public class InvalidReceiptFileException extends RuntimeException {

    public InvalidReceiptFileException(String message) {
        super(message);
    }
}
