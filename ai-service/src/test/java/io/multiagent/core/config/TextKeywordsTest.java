package io.multiagent.core.config;

import io.multiagent.core.config.TextKeywords.Group;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class TextKeywordsTest {

    private final TextKeywords keywords = TextKeywordsFixture.fromConfigMap();

    @Test
    void findMonth_coversConfiguredLanguagesAndAccentlessSpelling() {
        assertThat(keywords.findMonth("44 km par jour juin").getAsInt()).isEqualTo(6);
        assertThat(keywords.findMonth("frais de fevrier").getAsInt()).isEqualTo(2);
        assertThat(keywords.findMonth("trajets d'août").getAsInt()).isEqualTo(8);
        assertThat(keywords.findMonth("44 km round trip daily june").getAsInt()).isEqualTo(6);
        assertThat(keywords.findMonth("44 km ida y vuelta en septiembre").getAsInt()).isEqualTo(9);
    }

    @Test
    void findMonth_requiresWholeWord() {
        // "mai" ne doit pas être détecté dans "maison"
        assertThat(keywords.findMonth("trajet maison bureau")).isEmpty();
    }

    @Test
    void any_matchesAtWordStartOnly() {
        assertThat(keywords.any("44km aller-retour", Group.KM)).isTrue();
        assertThat(keywords.any("kilomètres parcourus", Group.KM)).isTrue();
        assertThat(keywords.any("40 km/jour", Group.DAILY)).isTrue();
        assertThat(keywords.any("44 km round trip", Group.ROUND_TRIP)).isTrue();
        assertThat(keywords.any("loyer bureau", Group.RENT)).isTrue();
        // "rent" ne doit pas matcher "différent" / "parent"
        assertThat(keywords.any("restaurant différent avec un parent", Group.RENT)).isFalse();
    }

    @Test
    void alternation_buildsUsableRegex() {
        Pattern p = Pattern.compile(keywords.alternation(Group.DELETE_TARGET) + "\\s*(\\d+)");
        assertThat(p.matcher("supprime la note de frais 6").find()).isTrue();
        assertThat(p.matcher("delete expense 6").find()).isTrue();
    }

    @Test
    void emptyGroup_neverMatches() {
        TextKeywords empty = new TextKeywords(new MockEnvironment());
        empty.load();
        assertThat(empty.any("44 km", Group.KM)).isFalse();
        assertThat(empty.findMonth("juin")).isEmpty();
        assertThat(Pattern.compile(empty.alternation(Group.DELETE_TARGET)).matcher("note de frais").find()).isFalse();
    }
}
