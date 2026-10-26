package io.multiagent.core.i18n;

import org.springframework.context.support.ResourceBundleMessageSource;

/** {@link Messages} sur les vrais fichiers i18n/messages*.properties — même configuration que application.yml. */
public final class MessagesFixture {

    private MessagesFixture() {}

    public static Messages standard() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setAlwaysUseMessageFormat(true);
        return new Messages(source, "fr", "fr,en,es,ar");
    }
}
