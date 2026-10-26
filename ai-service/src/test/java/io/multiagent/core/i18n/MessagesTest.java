package io.multiagent.core.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class MessagesTest {

    private final Messages messages = MessagesFixture.standard();

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
        LocaleContextHolder.resetLocaleContext();
    }

    private void request(String acceptLanguage) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (acceptLanguage != null) req.addHeader("Accept-Language", acceptLanguage);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    void defaultsToFrenchWithoutRequestOrHeader() {
        assertThat(messages.get("upload.error.tooLarge")).isEqualTo("Fichier trop volumineux (8 Mo maximum).");
        request(null);
        assertThat(messages.get("upload.error.tooLarge")).isEqualTo("Fichier trop volumineux (8 Mo maximum).");
    }

    @Test
    void followsAcceptLanguageAmongSupportedLocales() {
        request("en-US,en;q=0.9");
        assertThat(messages.get("upload.error.tooLarge")).isEqualTo("File too large (8 MB maximum).");
        request("de-DE");
        assertThat(messages.locale()).isEqualTo(Locale.forLanguageTag("fr"));
    }

    @Test
    void asyncThreadUsesPropagatedLocale() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("es"));
        assertThat(messages.get("receipt.error.invalidAmount")).isEqualTo("Importe no válido.");
    }

    @Test
    void getDefaultIgnoresRequestLanguage() {
        request("en");
        assertThat(messages.getDefault("expense.default.address")).isEqualTo("Paris, France");
        assertThat(messages.getDefault("notification.expense.approved", " du 2026-06-03"))
                .isEqualTo("Votre note de frais du 2026-06-03 a été approuvée.");
    }

    @Test
    void apostrophesAndArgumentsAreFormatted() {
        assertThat(messages.get("expense.error.noValidExpense")).contains("n'a pas pu");
        assertThat(messages.get("guard.error.tooLong", "5000", "4000"))
                .isEqualTo("Votre message est trop long (5000 caractères, maximum 4000).");
    }

    @Test
    void everyLanguageDefinesTheSameKeys() throws IOException {
        Properties fr = load("");
        for (String lang : new String[] {"_en", "_es", "_ar"}) {
            assertThat(load(lang).stringPropertyNames()).as("messages%s.properties", lang)
                    .containsExactlyInAnyOrderElementsOf(fr.stringPropertyNames());
        }
    }

    private Properties load(String suffix) throws IOException {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/i18n/messages" + suffix + ".properties")) {
            p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }
        return p;
    }
}
