package io.multiagent.core.config;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Properties;

/** {@link TextKeywords} chargé depuis la vraie ConfigMap — les tests vérifient les listes réellement déployées. */
public final class TextKeywordsFixture {

    static final String CONFIG_MAP = "deploy/k8s/configMap.yaml";

    private TextKeywordsFixture() {}

    public static TextKeywords fromConfigMap() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(CONFIG_MAP));
        Properties props = yaml.getObject();
        MockEnvironment env = new MockEnvironment();
        props.stringPropertyNames().stream()
                .filter(k -> k.startsWith("data."))
                .forEach(k -> env.setProperty(k.substring("data.".length()), props.getProperty(k)));
        TextKeywords keywords = new TextKeywords(env);
        keywords.load();
        return keywords;
    }
}
