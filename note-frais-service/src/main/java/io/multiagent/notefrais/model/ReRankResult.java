package io.multiagent.notefrais.model;

import lombok.Data;

/**
 * Élément individuel du résultat de reranking.
 */
@Data
public class ReRankResult {
    private String document;
    private double relevance;
    private int index;
}
