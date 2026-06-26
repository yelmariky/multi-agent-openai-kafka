package io.multiagent.core.util;

public class PromptBuilder {

    /**
     * Crée un prompt structuré et robuste.
     *
     * Format :
     *  [ROLE SECTION]
     *  [CONTEXT SECTION]
     *  [INSTRUCTIONS SECTION]
     */
    public static String build(String role, String context, String instruction) {
        return """
                ROLE:
                %s

                CONTEXT:
                %s

                INSTRUCTIONS:
                %s

                IMPORTANT:
                - Always answer in JSON
                - Do NOT invent missing fields
                """.formatted(
                safe(role),
                safe(context),
                safe(instruction));
    }

    /** Échappe les % littéraux pour éviter MissingFormatArgumentException si le texte contient "% d", "% s", etc. */
    private static String safe(String s) {
        return s == null ? "" : s.replace("%", "%%");
    }
}