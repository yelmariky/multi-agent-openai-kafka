package io.multiagent.notefrais.util;

public class PromptBuilder {

    /**
     * Crée un prompt structuré et robuste.
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
                """.formatted(role, context, instruction);
    }
}
