package io.multiagent.notefrais.reasoning;

import io.multiagent.notefrais.client.LLMAIClient;
import io.multiagent.notefrais.model.ReRankResponse;
import io.multiagent.notefrais.model.ReRankResult;
import io.multiagent.notefrais.model.ReRankScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service de re-ranking basé sur similarité cosinus via embeddings OpenAI.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReRankService {

    private final LLMAIClient llm;

    public ReRankResponse rerank(String query, List<String> documents) {
        if (documents == null || documents.isEmpty()) {
            log.warn("ReRankService.rerank appelé avec une liste de documents vide");
            ReRankResponse empty = new ReRankResponse();
            empty.setResults(List.of());
            return empty;
        }

        log.info("ReRankService → rerank {} documents pour la requête '{}'", documents.size(), query);

        List<ReRankScore> scores = llm.rerank(query, documents);

        if (scores == null || scores.isEmpty()) {
            log.warn("ReRankService → LLMAIClient.rerank a retourné une liste vide");
            ReRankResponse empty = new ReRankResponse();
            empty.setResults(List.of());
            return empty;
        }

        List<ReRankResult> results = scores.stream()
                .sorted(Comparator.comparingDouble(ReRankScore::getScore).reversed())
                .map(score -> {
                    int index = score.getIndex();
                    String doc = (index >= 0 && index < documents.size())
                            ? documents.get(index)
                            : "";

                    ReRankResult r = new ReRankResult();
                    r.setIndex(index);
                    r.setDocument(doc);
                    r.setRelevance(score.getScore());
                    return r;
                })
                .collect(Collectors.toList());

        ReRankResponse response = new ReRankResponse();
        response.setResults(results);

        log.info("ReRankService → rerank terminé, top1 score = {}",
                results.isEmpty() ? "n/a" : results.get(0).getRelevance());

        return response;
    }

    public List<String> rerankAndExtractTopK(String query, List<String> documents, int topK) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        int limit = (topK <= 0) ? documents.size() : Math.min(topK, documents.size());

        ReRankResponse response = rerank(query, documents);

        return response.getResults().stream()
                .limit(limit)
                .map(ReRankResult::getDocument)
                .toList();
    }
}
