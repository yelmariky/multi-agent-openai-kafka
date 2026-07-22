# Fine-tuning — Pourquoi et comment, dans ce projet ESN

## TL;DR

| Technique | Quand | Avantage principal |
|---|---|---|
| **Few-shot prompting** | Comportement général, flexible | Zéro coût d'entraînement, modifiable à chaud |
| **RAG** | Base de connaissance dynamique (docs, règles métier) | Connaissance fraîche, traçable, pas de réentraînement |
| **Fine-tuning** | Tâche répétitive, format fixe, volume élevé | Tokens × 3–5× moins chers, latence réduite, cohérence garantie |

---

## Le problème concret : classification des notes de frais

Ce projet traite en texte libre des notes de frais comme :

> *"Taxi de Paris Gare de Lyon à La Défense pour réunion client Société Générale, 34,50€"*

Aujourd'hui, `RAGService` + prompt GPT-4.1-mini extrait la catégorie, le montant et la facturation client. Ça marche, mais :

- **Coût** : chaque requête envoie un system prompt de ~200 tokens + quelques exemples few-shot → ~400 tokens à chaque appel
- **Inconsistance** : le modèle peut occasionnellement retourner `"transport"` au lieu de `"TRANSPORT"`, ou oublier `billable`
- **Latence** : le modèle de base est généreux mais lent pour une tâche aussi mécanique

Le fine-tuning résout ces trois points pour les tâches à **format de sortie fixe et volume prévisible**.

---

## Quand ne PAS fine-tuner

**Utilisez RAG à la place si** :
- Les données sources changent fréquemment (nouvelles règles de remboursement, barèmes km mis à jour)
- Vous avez besoin de traçabilité ("pourquoi ce choix ?") → RAG peut citer les chunks sources
- Le volume est faible (< 1000 requêtes/jour) — le ROI n'est pas atteint

**Utilisez le few-shot prompting à la place si** :
- La tâche est encore en exploration — vous tâtonnez sur le format de sortie
- Vous avez moins de 50 exemples d'entraînement — trop peu pour fine-tuner correctement

**Le fine-tuning est pertinent quand** :
- La tâche est stabilisée (format de sortie gelé depuis plusieurs sprints)
- Vous avez ≥ 50 exemples vérifiés (idéalement 200+)
- L'économie est calculée : `coût_tokens_economisés × volume > coût_fine_tuning_job`

---

## Calcul ROI pour ce projet

Données d'entraînement du fichier `expense-classification-training.jsonl` (20 exemples) :
→ En production, viser **200 exemples minimum** avant de lancer le job.

| Paramètre | Valeur |
|---|---|
| Volume moyen notes de frais | ~500 req/jour (ESN 50 consultants) |
| System prompt today (few-shot) | ~400 tokens/req |
| System prompt fine-tuné | ~80 tokens/req (prompt minimaliste, format baked-in) |
| Économie tokens | 320 tokens × 500 req = 160 000 tokens/jour |
| Coût gpt-4.1-mini input | $0,40/1M tokens |
| Économie journalière | ~$0,064/jour → ~$23/an |
| Coût job fine-tuning (200 ex.) | ~$0,20 |
| **Payback** | **4 jours** |

Le ROI devient significatif si on additionne **tous** les champs extraits (montant, devise, catégorie, justification, billable) dans une seule passe plutôt que 5 appels séparés.

---

## Architecture d'intégration dans ce projet

```
[Consultant soumet note de frais en texte libre]
          |
          v
   PromptGuardFilter (@Order(1))   ← injection / rate-limit
          |
          v
   ExpenseController.createFromText()
          |
          v
   OcrExtractionService (si PDF)   ← Tesseract + poppler
          |
          v
   FineTunedExpenseClassifier      ← appelle le modèle ft:gpt-4.1-mini-...:esn-expense-v1
     (remplace l'appel RAGService pour la classification)
          |
          v
   ExpenseEntity.save() → Kafka → admin notifié
```

Le fine-tuned model remplace l'appel LLM dans la classification initiale.  
Le RAG reste utilisé pour les **questions de remboursement** ("ai-je droit à l'hôtel ?") où la base documentaire est dynamique.

---

## Le workflow OpenAI en 3 étapes

### 1. Préparer les données (JSONL)

Chaque ligne est une conversation complète :

```jsonl
{"messages": [
  {"role": "system", "content": "Tu es un assistant de classification..."},
  {"role": "user",   "content": "Taxi CDG → client, 52€"},
  {"role": "assistant", "content": "{\"category\": \"TRANSPORT\", \"amount\": 52.00, ...}"}
]}
```

Fichier fourni : `ai-service/src/main/resources/fine-tuning/expense-classification-training.jsonl`  
→ 20 exemples couvrant toutes les catégories (TRANSPORT, HEBERGEMENT, RESTAURATION, MATERIEL, FORMATION, AUTRE), avec nuances billable/non-billable.

### 2. Lancer le job (Java SDK)

```java
// Upload
FileObject file = client.files().create(FileCreateParams.builder()
    .file(trainingFilePath)
    .purpose(FileCreateParams.Purpose.FINE_TUNE)
    .build());

// Job
FineTuningJob job = client.fineTuning().jobs().create(
    FineTuningJobCreateParams.builder()
        .trainingFile(file.id())
        .model("gpt-4.1-mini-2025-07-18")
        .suffix("esn-expense-v1")
        .build()
);
// Job ID : ft-xxxx  →  modèle final : ft:gpt-4.1-mini-2025-07-18:org:esn-expense-v1:xxxx
```

Durée typique : 15–45 min pour 20 exemples / 3 epochs.

### 3. Utiliser le modèle fine-tuné

```java
// Le model ID est récupéré depuis une variable d'environnement en production
String modelId = System.getenv("OPENAI_FINE_TUNED_MODEL"); // ft:gpt-4.1-mini-...:esn-expense-v1:xxxx

ChatCompletion resp = client.chat().completions().create(
    ChatCompletionCreateParams.builder()
        .model(modelId)
        .messages(List.of(
            ChatCompletionMessageParam.ofSystem(systemMsg),
            ChatCompletionMessageParam.ofUser(expenseText)
        ))
        .build()
);
```

---

## Code de démonstration

`FineTuningExample.java` : `io.multiagent.core.finetuning.FineTuningExample`

Activez avec le profil Spring `fine-tuning-demo` :
```bash
OPENAI_API_KEY=sk-... mvn spring-boot:run -Dspring-boot.run.profiles=fine-tuning-demo
```

Ce `CommandLineRunner` exécute les 3 étapes (upload → job → poll → inférence) et loggue les résultats.  
**Ne pas activer en production** — c'est un outil one-shot pour lancer le premier fine-tuning.

---

## Variables d'environnement à ajouter (K8s ConfigMap/Secret)

```yaml
# ai-service/deploy/k8s/secret.yaml
OPENAI_FINE_TUNED_MODEL: "ft:gpt-4.1-mini-2025-07-18:org:esn-expense-v1:xxxx"
```

```yaml
# application.yml
openai:
  fine-tuned-model: ${OPENAI_FINE_TUNED_MODEL:}  # vide = utilise le modèle de base
```

Si `OPENAI_FINE_TUNED_MODEL` est vide, le code retombe sur `gpt-4.1-mini` standard (comportement actuel inchangé).

---

## Quand ré-entraîner ?

- Chaque trimestre si les consultants reportent des erreurs de classification > 5%
- Après ajout d'une nouvelle catégorie de dépense
- Si les règles de remboursement changent significativement (e.g., nouveau barème km)

Le JSONL d'entraînement est versionné dans le repo — chaque ré-entraînement = un nouveau fichier versionné (`expense-classification-training-v2.jsonl`).
