package io.multiagent.notefrais.model;

import lombok.Data;

@Data
public class RAGChunk {

    /** Identifiant logique du chunk */
    private String id;

    /** Texte du chunk */
    private String text;

    /** Source (weaviate, fichier, base, etc.) */
    private String source;
}
