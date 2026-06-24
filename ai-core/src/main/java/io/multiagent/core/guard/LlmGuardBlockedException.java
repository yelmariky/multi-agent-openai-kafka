package io.multiagent.core.guard;

/**
 * Levée quand LLM Guard détecte une menace (injection, PII, secret…).
 * Non-retryable — doit remonter à l'appelant immédiatement.
 */
public class LlmGuardBlockedException extends RuntimeException {

    private final String scanner;
    private final double score;

    public LlmGuardBlockedException(String scanner, double score, String message) {
        super(message);
        this.scanner = scanner;
        this.score = score;
    }

    public String getScanner() { return scanner; }
    public double getScore()   { return score; }
}
