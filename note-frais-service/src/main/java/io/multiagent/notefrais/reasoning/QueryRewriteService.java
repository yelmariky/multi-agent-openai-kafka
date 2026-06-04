package io.multiagent.notefrais.reasoning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.notefrais.client.LLMAIClient;
import io.multiagent.notefrais.util.DateProvider;
import io.multiagent.notefrais.util.LLMUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Service de réécriture de requête (Query Rewrite),
 * pour améliorer le rappel/pertinence du RAG.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriteService implements InitializingBean {

    private final LLMAIClient llm;
    private final DateProvider dateProvider;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${ai-core.openai.rewrite-model:gpt-4o-mini}")
    private String rewriteModel;

    @Value("${AI_CORE_PROMPT_REWRITE_SYSTEM:}")
    private String rewriteSystemPromptEnv;

    private String systemPromptTemplate;

    @Override
    public void afterPropertiesSet() throws Exception {
        if (rewriteSystemPromptEnv != null && !rewriteSystemPromptEnv.isBlank()) {
            systemPromptTemplate = rewriteSystemPromptEnv;
        } else {
            throw new IllegalStateException("AI_CORE_PROMPT_REWRITE_SYSTEM doit être fourni via ConfigMap/ENV");
        }
    }

    @Cacheable("queryRewrite")
    public String rewrite(String query) {
        log.info("QueryRewriteService.rewrite()");

        String todayStr = dateProvider.todayUtc().toString();
        String system = systemPromptTemplate.formatted(todayStr);

        String user = """
                Voici la requête utilisateur :
                "%s"

                Donne uniquement le JSON demandé.
                """.formatted(query);

        try {
            var completion = llm.chatJson(rewriteModel, system, user);
            String content = LLMUtils.extractChatContent(completion);

            JsonNode json = mapper.readTree(content);
            String rewrite = json.path("rewrite").asText(query);

            log.debug("Rewrite: '{}' → '{}'", query, rewrite);
            return rewrite;

        } catch (Exception e) {
            log.warn("QueryRewriteService: échec du rewrite, fallback à la requête originale. Cause: {}",
                    e.getMessage());
            return query;
        }
    }
}
