# Activer le chatbot IA (LLM) sur le site vitrine

Aujourd'hui le chatbot du site répond en mode **scripté** (réponses pré-écrites, 100% navigateur).
Le backend IA est **prêt dans le code** : un endpoint public `POST /public/chatbot` sur ai-service,
qui utilise votre pipeline Groq → fallback OpenAI existant.

## Architecture

```
Site IONOS (script.js) ──POST {message}──▶ Kong /public/chatbot ──▶ ai-service ChatbotController
                                                │                        │
                                     rate-limit 20/min + CORS      PromptGuardFilter (injection, sanitize)
                                                                         │
                                                                   ChatbotService (rate-limit 8/min/IP)
                                                                         │
                                                                   LLMAIClient → Groq (fallback OpenAI)
```

## Sécurité en place (endpoint anonyme = surface d'attaque)

| Couche | Protection |
|---|---|
| Kong | 20 req/min global sur la route, CORS restreint à `ia-insightservices.fr`, POST uniquement |
| PromptGuardFilter | inspecte le champ `message` : taille, injection de prompt, assainissement |
| ChatbotService | 8 req/min/IP + 60/min global, entrée tronquée à 500 caractères, sortie à 1 200 |
| Prompt système | faits verrouillés (tarifs, contact), interdiction d'inventer, anti-jailbreak |
| Widget | la réponse LLM est insérée en texte brut (`textContent`) — jamais interprétée en HTML |

## Étapes d'activation

### 1. Déployer le backend

```bash
mvn clean package -DskipTests
cd ai-service && docker build -t dokeryelmariki/ai-service:latest . && docker push dokeryelmariki/ai-service:latest
kubectl apply -f ai-service/deploy/k8s/          # inclut le nouveau prompt AI_CORE_PROMPT_CHATBOT_SYSTEM
kubectl rollout restart deployment/ai-service -n multi-agent
kubectl apply -f deploy/gateway/configmap.yaml   # nouvelle route Kong /public/chatbot
kubectl rollout restart deployment/kong -n kong  # Kong DB-less : rollout obligatoire après modif config
```

### 2. Exposer Kong publiquement

Le site (hébergé chez IONOS) doit pouvoir joindre Kong en HTTPS. Il vous faut une URL publique,
par exemple `https://api.ia-insightservices.fr` pointant vers le LoadBalancer/Ingress de Kong.
Créez le sous-domaine `api` dans la zone DNS IONOS (enregistrement A vers l'IP publique de Kong).

### 3. Brancher le widget

Dans `script.js`, remplacez la constante vide :

```js
const CHATBOT_API = 'https://api.ia-insightservices.fr/public/chatbot';
```

### 4. Autoriser l'appel dans les CSP

L'origine de l'API doit être ajoutée dans **les deux** politiques de sécurité :

- `index.html`, balise `<meta http-equiv="Content-Security-Policy">` : ajouter
  `connect-src 'self' https://api.ia-insightservices.fr;`
- `.htaccess`, en-tête `Content-Security-Policy` : même ajout.

### 5. Tester

```bash
curl -X POST https://api.ia-insightservices.fr/public/chatbot \
  -H "Content-Type: application/json" \
  -H "Origin: https://ia-insightservices.fr" \
  -d '{"message":"Quels sont vos tarifs ?"}'
# Attendu : {"reply":"Nos offres démarrent à 29€/consultant/mois…"}
```

Puis re-uploadez `script.js`, `index.html` et `.htaccess` sur IONOS.

## Bon à savoir

- **Si l'API tombe** (pod down, quota LLM), le widget **retombe automatiquement sur le bot scripté** —
  le visiteur ne voit jamais d'erreur.
- **Coût** : Groq est gratuit ; seul le fallback OpenAI facture (gpt-4.1-mini, quelques centimes pour
  des centaines de conversations).
- **Modifier le ton/les faits du bot** : éditez `AI_CORE_PROMPT_CHATBOT_SYSTEM` dans
  `ai-service/deploy/k8s/configMap.yaml` puis `kubectl apply` + restart du pod (convention du projet).
