package io.multiagent.core.i18n;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Textes destinés à l'utilisateur — fichiers {@code i18n/messages*.properties}.
 * <ul>
 *   <li>{@link #get} : langue de la requête courante (en-tête Accept-Language, restreint aux langues
 *       de {@code AI_CORE_TEXT_LOCALES}), sinon langue par défaut ;</li>
 *   <li>{@link #getDefault} : langue par défaut ({@code AI_CORE_DEFAULT_LOCALE}) — notifications envoyées
 *       à un tiers, textes stockés en base, traitements planifiés.</li>
 * </ul>
 */
@Component
public class Messages {

    private final MessageSource source;
    private final Locale defaultLocale;
    private final List<Locale> supported;

    public Messages(MessageSource source,
                    @Value("${AI_CORE_DEFAULT_LOCALE:fr}") String defaultLocale,
                    @Value("${AI_CORE_TEXT_LOCALES:fr}") String supportedLocales) {
        this.source = source;
        this.defaultLocale = Locale.forLanguageTag(defaultLocale.trim());
        this.supported = Arrays.stream(supportedLocales.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(Locale::forLanguageTag).toList();
    }

    public String get(String key, Object... args) {
        return source.getMessage(key, args, locale());
    }

    public String getDefault(String key, Object... args) {
        return source.getMessage(key, args, defaultLocale);
    }

    /** Mois + année dans la langue donnée (ex. "juin 2026", "June 2026"). */
    public static String monthYear(YearMonth ym, Locale locale) {
        return ym.getMonth().getDisplayName(TextStyle.FULL, locale) + " " + ym.getYear();
    }

    public Locale defaultLocale() {
        return defaultLocale;
    }

    /** Langue de la requête HTTP courante ; hors requête, celle propagée aux tâches async ; sinon la langue par défaut. */
    public Locale locale() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return fromHeader(attrs.getRequest());
        }
        LocaleContext ctx = LocaleContextHolder.getLocaleContext();
        if (ctx != null && ctx.getLocale() != null) return match(ctx.getLocale().toLanguageTag());
        return defaultLocale;
    }

    private Locale fromHeader(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.ACCEPT_LANGUAGE);
        return header == null || header.isBlank() ? defaultLocale : match(header);
    }

    private Locale match(String languageRanges) {
        try {
            Locale found = Locale.lookup(Locale.LanguageRange.parse(languageRanges), supported);
            return found != null ? found : defaultLocale;
        } catch (IllegalArgumentException e) {
            return defaultLocale;
        }
    }
}
