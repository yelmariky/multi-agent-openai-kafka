# 10 posts prêts à publier — IA-INSIGHT

> Chaque post : hook (2 premières lignes = tout se joue là), corps, CTA, hashtags, format, qui publie.
> Personnaliser les [crochets] avant publication.

---

## Post #1 — Vision fondateur (profil fondateur)

**Format :** texte + photo du fondateur ou de l'équipe
**Cible :** toutes

```
J'ai vu des consultants brillants passer leurs dimanches soirs
à ressaisir des tickets de restaurant dans un tableur.

C'est ce qui m'a décidé à créer IA-INSIGHT.

Dans les ESN et cabinets de conseil IT, le back-office est resté
bloqué en 2010 :
→ notes de frais ressaisies à la main
→ CRA relancés par email 5 fois par mois
→ factures générées à la main, envoyées en retard

Pendant ce temps, l'IA sait lire un reçu, comprendre une phrase
comme « resto client 45€ hier à Lyon », et générer la facture
dès que le CRA est validé.

Alors on l'a construit :
✅ Notes de frais en langage naturel ou photo (OCR + IA)
✅ CRA avec validation et notifications temps réel
✅ Facturation automatique à la validation
✅ Gouvernance IA intégrée (RGPD, audit, garde-fous)

On démarre. La suite : premiers clients pilotes, et je partagerai
tout ici — les réussites comme les galères.

Si vous dirigez une ESN et que ces sujets vous parlent,
suivez IA-INSIGHT ou écrivez-moi directement.

#IA #ESN #SaaS #Startup
```

---

## Post #2 — Douleur métier notes de frais (page)

**Format :** texte court percutant
**Cible :** dirigeants ESN

```
Une note de frais coûte en moyenne 53 € à traiter manuellement.
(source : GBTA)

Pour une ESN de 50 consultants avec 8 notes/mois chacun :
→ 400 notes de frais mensuelles
→ 21 200 € de coût de traitement par mois
→ 254 000 € par an

Le pire ? Ce n'est pas le coût. C'est que :
• vos consultants détestent ça
• votre compta détecte les erreurs trop tard
• vos remboursements prennent 3 semaines

Avec IA-INSIGHT, le consultant écrit « hôtel 120€ mardi à Nantes »
ou photographie son reçu. L'IA structure, catégorise, vérifie.
L'admin valide en un clic.

Combien de temps votre équipe passe-t-elle sur les notes de frais
chaque mois ? Dites-le-nous en commentaire 👇

#ESN #NotesDeFrais #IA #Automatisation
```

---

## Post #3 — Carrousel « 5 processus à automatiser » (page)

**Format :** carrousel PDF 7 slides (1128×1410 px, fond sombre #0d1017, accent #2ce5a7)
**Cible :** dirigeants ESN

```
Texte d'accompagnement :

5 processus que votre ESN peut automatiser dès demain avec l'IA 🧵

Slide 1 (couverture) : « 5 processus qu'une ESN peut automatiser
avec l'IA — et combien ça rapporte »
Slide 2 : Notes de frais — OCR + langage naturel → -80 % de temps de saisie
Slide 3 : CRA — workflow de validation + relances automatiques → zéro oubli
Slide 4 : Facturation — générée à la validation du CRA → -15 jours de délai
Slide 5 : Congés — solde temps réel intégré au CRA → fini les tableurs
Slide 6 : Recherche documentaire interne — RAG sur vos documents → réponses en 5 s
Slide 7 (CTA) : « IA-INSIGHT automatise tout ça dans une seule
plateforme. Suivez-nous ou demandez une démo. »

Sauvegardez ce carrousel pour votre prochain comité de direction 📌

#ESN #IA #Automatisation #SaaS #ConseilIT
```

---

## Post #4 — Feature CRA (fondateur)

**Format :** texte + GIF/capture d'écran du workflow
**Cible :** dirigeants ESN

```
Le 5 du mois. Votre office manager envoie son 3e email :
« Merci de soumettre vos CRA pour la facturation. »

Ce rituel coûte à votre ESN bien plus qu'un agacement :
chaque jour de retard de CRA = un jour de retard de facturation
= trésorerie qui se tend.

Voici comment ça se passe avec IA-INSIGHT :

1️⃣ Le consultant remplit son CRA en 2 minutes (jours travaillés,
   absences pré-remplies depuis ses congés validés)
2️⃣ Il clique sur Soumettre → l'admin est notifié en temps réel
3️⃣ L'admin valide → la facture PDF est générée automatiquement
   avec le bon TJM, le bon client, les bonnes mentions
4️⃣ Refus ? Le consultant voit le motif instantanément et corrige

Résultat : des CRA validés le 2 du mois au lieu du 15.

Comment gérez-vous les relances CRA aujourd'hui ? 👇

#CRA #ESN #Facturation #SaaS
```

---

## Post #5 — Architecture RAG (page)

**Format :** texte technique + schéma d'architecture simplifié
**Cible :** tech/IA

```
Comment on a construit notre pipeline RAG sans exploser les coûts 🔧

Notre stack IA chez IA-INSIGHT :

→ Embeddings : OpenAI text-embedding-3-large
→ Vector store : PostgreSQL + pgvector (index HNSW, cosine)
   Pas de base vectorielle dédiée : Postgres fait très bien le job
   jusqu'à plusieurs millions de vecteurs.
→ LLM : Groq (llama-3.1-8b pour l'intent, llama-4-scout pour le RAG)
   → gratuit et ultra-rapide
→ Fallback automatique : si Groq est indisponible, bascule
   transparente sur OpenAI gpt-4.1-mini (circuit breaker resilience4j)

Pourquoi ce choix ?
• Coût LLM quasi nul en fonctionnement nominal
• Latence < 1 s sur l'intent detection
• Zéro downtime IA : le fallback prend le relais en silence

La leçon : avant d'ajouter une brique d'infra dédiée,
regardez ce que votre Postgres sait déjà faire.

Des questions sur ce setup ? On répond en commentaire.

#RAG #IA #PostgreSQL #Engineering #LLM
```

---

## Post #6 — OCR notes de frais (page)

**Format :** vidéo écran 30-45 s ou GIF avant/après
**Cible :** toutes

```
📸 → 🧾 en 5 secondes.

Le consultant photographie son ticket de caisse.
L'IA extrait : montant, date, TVA, catégorie, mode de paiement.
La note de frais est créée, prête à valider.

Pas de formulaire. Pas de ressaisie. Pas d'erreur de saisie.

Sous le capot : OCR Tesseract + LLM pour la structuration
+ garde-fous métier (montant élevé → revue humaine obligatoire,
date future → alerte, catégorie inconnue → flag).

Parce qu'automatiser ne veut pas dire perdre le contrôle :
chaque décision IA est tracée et auditable.

Testez sur votre pire ticket froissé — on adore les défis 😄

#OCR #IA #NotesDeFrais #ESN
```

---

## Post #7 — Multi-tenant vulgarisé (page)

**Format :** texte + schéma simple
**Cible :** tech + investisseurs

```
« Vos données sont-elles vraiment isolées de celles des autres
clients ? »

C'est LA question que posent les DSI d'ESN. Et ils ont raison.

Notre réponse chez IA-INSIGHT : l'isolation par design.

→ Chaque client (tenant) = son propre espace d'authentification
  dédié (realm Keycloak isolé)
→ Chaque requête est filtrée par tenant au niveau applicatif ET
  au niveau des données
→ URL dédiée par organisation : votre-esn.ia-insight.fr
→ Provisioning automatisé : un nouveau client est opérationnel
  en quelques minutes, sans intervention manuelle

Ce n'est pas la façon la plus rapide de construire un SaaS.
C'est la façon dont on gagne la confiance d'un DSI.

L'architecture multi-tenant, sujet sous-coté ? Votre avis 👇

#SaaS #Sécurité #MultiTenant #B2B #Architecture
```

---

## Post #8 — Gouvernance IA (page)

**Format :** texte
**Cible :** dirigeants ESN + investisseurs

```
L'IA en entreprise sans gouvernance, c'est un stagiaire brillant
sans manager : ça finit mal.

Chez IA-INSIGHT, la gouvernance IA n'est pas une slide,
c'est du code en production :

🛡️ Garde-fous métier : une note de frais > 500 €, une date future,
   une catégorie inconnue → revue humaine obligatoire. L'IA propose,
   l'humain décide.

📋 Audit complet : chaque appel IA est journalisé (modèle, tokens,
   durée, score de confiance) — sans jamais stocker le texte brut
   de vos consultants (hash SHA-256, conformité RGPD).

🔒 Anti-injection : chaque requête passe par un filtre de sécurité
   qui bloque les tentatives de manipulation du LLM.

⚖️ Et bientôt : scan automatique des prompts par LLM Guard
   dans notre CI/CD — chaque livraison est testée contre un
   dataset d'attaques connues.

L'AI Act arrive. Les ESN qui choisissent leurs outils IA
regarderont ça de très près.

#GouvernanceIA #RGPD #IA #AIAct #ESN
```

---

## Post #9 — Sondage CRA (page)

**Format :** sondage LinkedIn (4 options, durée 1 semaine)
**Cible :** dirigeants ESN

```
Question : Combien d'heures par mois votre organisation
perd-elle sur la gestion des CRA (saisie, relances, validation,
refacturation) ?

Options du sondage :
• Moins de 5 h
• 5 à 20 h
• 20 à 50 h
• Plus de 50 h 😱

Texte d'accompagnement :
On pose la question parce que les réponses qu'on entend en
rendez-vous nous surprennent à chaque fois.

Les relances CRA, c'est le marronnier de la fin de mois en ESN.
Et chaque jour de retard = un jour de facturation décalée.

Votez et dites-nous en commentaire comment vous gérez ça
aujourd'hui (tableur ? outil maison ? relances manuelles ?) 👇

#CRA #ESN #Sondage #GestionEntreprise
```

---

## Post #10 — Bilan mois 1 + roadmap (fondateur)

**Format :** texte transparent, chiffres réels
**Cible :** investisseurs + écosystème

```
1 mois après le lancement de la page IA-INSIGHT.
Bilan transparent, chiffres réels :

📊 Où on en est
→ [X] organisations en test / pilote
→ [X] notes de frais traitées par l'IA
→ [X] CRA validés dans l'outil
→ [X] abonnés sur cette page (merci 🙏)

🔭 Ce qu'on a appris
→ [Apprentissage 1 — ex. : l'OCR est la feature qui déclenche
   le « wahou » en démo]
→ [Apprentissage 2 — ex. : les dirigeants demandent d'abord
   la conformité RGPD, avant même le prix]

🗺️ La suite (T[X] 2026)
→ [Jalon roadmap 1]
→ [Jalon roadmap 2]
→ Ouverture de [X] places de pilote supplémentaires

Je documente la construction d'IA-INSIGHT en public, les
victoires comme les murs. Suivez la page pour la suite.

Une ESN dans votre réseau qui devrait tester ? Taguez-la 👇

#BuildInPublic #SaaS #IA #Startup #ESN
```

---

## Règles de rédaction (pour les futurs posts)

1. **Le hook fait tout** : les 2 premières lignes doivent donner envie de cliquer « voir plus » — chiffre choc, situation vécue, question dérangeante
2. Une idée par post, phrases courtes, sauts de ligne fréquents
3. Toujours finir par une question ou un CTA
4. 3-5 hashtags, jamais plus
5. Pas de lien externe dans le corps du post (l'algorithme pénalise) → lien en premier commentaire
6. Émojis avec parcimonie : structure (→ ✅ 📊), pas décoration
7. Publier, puis répondre à chaque commentaire dans l'heure
