package io.multiagent.core.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Mots-clés de détection sur le texte libre (règles de secours quand le LLM est indisponible).
 * Aucun mot n'est codé en dur : chaque groupe est lu depuis la ConfigMap ({@code AI_CORE_KEYWORDS_<GROUPE>},
 * liste séparée par des virgules, toutes langues mélangées). Les noms de mois viennent du JDK pour les
 * langues de {@code AI_CORE_TEXT_LOCALES}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TextKeywords {

    public enum Group { KM, ROUND_TRIP, DAILY, RENT, BUSINESS_PAYMENT, DELETE_TARGET, LIST_SEPARATORS, COMPANY_PREFIX, UNKNOWN_PAYMENT, PROMPT_LEAK, DELETE_VERBS, INVOICE_PLURAL, INVOICE_BULK }

    private static final String KEY_PREFIX = "AI_CORE_KEYWORDS_";
    private static final Pattern NEVER = Pattern.compile("(?!)");

    private final Environment env;
    private final Map<Group, List<String>> groups = new EnumMap<>(Group.class);
    private final Map<Group, Pattern> prefixPatterns = new EnumMap<>(Group.class);
    private final List<Map.Entry<Pattern, Integer>> monthPatterns = new ArrayList<>();

    @PostConstruct
    void load() {
        for (Group g : Group.values()) {
            List<String> words = split(env.getProperty(KEY_PREFIX + g.name(), ""));
            if (words.isEmpty()) log.warn("⚠️ {}{} vide — règle de détection désactivée", KEY_PREFIX, g.name());
            groups.put(g, words);
            prefixPatterns.put(g, words.isEmpty() ? NEVER : Pattern.compile(words.stream()
                    // borne de début de mot seulement si le mot-clé commence par une lettre ("/jour" doit matcher "km/jour")
                    .map(w -> (Character.isLetter(w.charAt(0)) ? "(?<!\\p{L})" : "") + phraseRegex(w))
                    .collect(java.util.stream.Collectors.joining("|")), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
        Map<String, Integer> names = new LinkedHashMap<>();
        for (String tag : split(env.getProperty("AI_CORE_TEXT_LOCALES", ""))) {
            Locale locale = Locale.forLanguageTag(tag);
            for (Month m : Month.values()) {
                for (TextStyle style : List.of(TextStyle.FULL, TextStyle.FULL_STANDALONE)) {
                    String name = m.getDisplayName(style, locale).toLowerCase(locale);
                    names.putIfAbsent(name, m.getValue());
                    names.putIfAbsent(stripAccents(name), m.getValue());
                }
            }
        }
        names.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getKey().length()).reversed())
                .forEach(e -> monthPatterns.add(Map.entry(
                        Pattern.compile("(?<!\\p{L})" + Pattern.quote(e.getKey()) + "(?!\\p{L})"), e.getValue())));
        if (monthPatterns.isEmpty()) log.warn("⚠️ AI_CORE_TEXT_LOCALES vide — détection du mois désactivée");
    }

    public List<String> list(Group g) {
        return groups.getOrDefault(g, List.of());
    }

    /**
     * Vrai si {@code text} contient un mot-clé du groupe commençant en début de mot (insensible à la casse) :
     * "kilom" matche "kilomètres" et "44km", mais "rent" ne matche pas "différent".
     */
    public boolean any(String text, Group g) {
        if (text == null || text.isBlank()) return false;
        return prefixPatterns.getOrDefault(g, NEVER).matcher(text).find();
    }

    /** Numéro du premier mois (1-12) nommé dans le texte, dans l'une des langues configurées. */
    public OptionalInt findMonth(String text) {
        if (text == null) return OptionalInt.empty();
        String lower = text.toLowerCase(Locale.ROOT);
        String noAccents = stripAccents(lower);
        for (Map.Entry<Pattern, Integer> e : monthPatterns) {
            if (e.getKey().matcher(lower).find() || e.getKey().matcher(noAccents).find()) {
                return OptionalInt.of(e.getValue());
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Alternative regex {@code (?:a|b|c)} construite à partir du groupe : chaque expression est échappée,
     * ses espaces deviennent {@code \s+}, les plus longues passent en premier. Groupe vide → ne matche jamais.
     */
    public String alternation(Group g) {
        List<String> words = list(g);
        if (words.isEmpty()) return NEVER.pattern();
        StringJoiner joiner = new StringJoiner("|", "(?:", ")");
        words.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(TextKeywords::phraseRegex)
                .forEach(joiner::add);
        return joiner.toString();
    }

    private static String phraseRegex(String phrase) {
        StringJoiner joiner = new StringJoiner("\\s+");
        for (String part : phrase.trim().split("\\s+")) joiner.add(Pattern.quote(part));
        return joiner.toString();
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private static String stripAccents(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
