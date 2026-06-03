package io.multiagent.invoice.util;

import com.openai.models.chat.completions.ChatCompletion;

public final class LLMUtils {

    private LLMUtils() {}

    public static String extractChatContent(ChatCompletion completion) {
        if (completion == null || completion.choices() == null) {
            return "";
        }
        return completion.choices().stream()
                .findFirst()
                .flatMap(c -> c.message().content())
                .orElse("");
    }
}
