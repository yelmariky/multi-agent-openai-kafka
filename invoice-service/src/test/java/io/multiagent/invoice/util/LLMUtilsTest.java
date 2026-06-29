package io.multiagent.invoice.util;

import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletion.Choice;
import com.openai.models.chat.completions.ChatCompletionMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("LLMUtils — extraction contenu ChatCompletion")
class LLMUtilsTest {

    @Test
    @DisplayName("extractChatContent() retourne le contenu du premier choice")
    void extractsFirstChoiceContent() {
        ChatCompletion completion = mockCompletion("Bonjour");
        assertThat(LLMUtils.extractChatContent(completion)).isEqualTo("Bonjour");
    }

    @Test
    @DisplayName("extractChatContent() retourne '' si completion null")
    void returnsEmptyOnNullCompletion() {
        assertThat(LLMUtils.extractChatContent(null)).isEmpty();
    }

    @Test
    @DisplayName("extractChatContent() retourne '' si choices vide")
    void returnsEmptyOnEmptyChoices() {
        ChatCompletion completion = mock(ChatCompletion.class);
        when(completion.choices()).thenReturn(List.of());
        assertThat(LLMUtils.extractChatContent(completion)).isEmpty();
    }

    @Test
    @DisplayName("extractChatContent() retourne '' si content absent (Optional.empty)")
    void returnsEmptyOnAbsentContent() {
        ChatCompletion completion = mock(ChatCompletion.class);
        Choice choice = mock(Choice.class);
        ChatCompletionMessage msg = mock(ChatCompletionMessage.class);
        when(completion.choices()).thenReturn(List.of(choice));
        when(choice.message()).thenReturn(msg);
        when(msg.content()).thenReturn(Optional.empty());
        assertThat(LLMUtils.extractChatContent(completion)).isEmpty();
    }

    private ChatCompletion mockCompletion(String content) {
        ChatCompletion completion = mock(ChatCompletion.class);
        Choice choice = mock(Choice.class);
        ChatCompletionMessage msg = mock(ChatCompletionMessage.class);
        when(completion.choices()).thenReturn(List.of(choice));
        when(choice.message()).thenReturn(msg);
        when(msg.content()).thenReturn(Optional.of(content));
        return completion;
    }
}
