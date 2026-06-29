package io.multiagent.expense.util;

import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseOutputMessage.Content;
import java.util.Objects;

public final class LLMUtils {

    private LLMUtils() {}

    public static String extractText(Response response) {
        if (response == null || response.output() == null) {
            return "";
        }

        var outputs = response.output();
        if (outputs.isEmpty()) {
            return "";
        }

        for (var item : outputs) {
            var messageOpt = item.message();
            if (messageOpt.isEmpty()) {
                continue;
            }

            var message = messageOpt.get();
            var contents = message.content();
            if (contents == null || contents.isEmpty()) {
                continue;
            }

            for (Content content : contents) {
                var outputTexts = content.outputText();
                if (outputTexts == null || outputTexts.isEmpty()) {
                    continue;
                }
                var txt = outputTexts.get().text();
                if (txt != null) {
                    return txt;
                }
            }
        }

        return "";
    }

    public static String extractChatContent(ChatCompletion completion) {
        if (completion == null || completion.choices() == null) {
            return "";
        }

        return completion.choices().stream()
                .filter(Objects::nonNull)
                .map(choice -> choice.message())
                .filter(Objects::nonNull)
                .map(message -> message.content().orElse(""))
                .filter(content -> content != null && !content.isBlank())
                .findFirst()
                .orElse("");
    }
}
