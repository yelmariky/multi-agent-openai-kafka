# Déployer le site IA-INSIGHT sur IONOS

Le site est 100% statique — aucun serveur, aucune base de données. Il se déploie sur n'importe quel hébergement web IONOS.

**Fichiers à mettre en ligne** : `index.html`, `style.css`, `script.js`, `.htaccess`, `robots.txt`.
⚠️ Le `.htaccess` est **obligatoire** : il empêche le listing des fichiers du site, force le HTTPS et ajoute les en-têtes de sécurité (CSP, X-Frame-Options, HSTS…). Ne déployez PAS ce fichier `DEPLOY-IONOS.md` (et le `.htaccess` bloque de toute façon l'accès aux `.md`).

## Option A — Espace web IONOS classique (SFTP) — recommandé

1. **Récupérer les identifiants SFTP** : espace client IONOS → *Hébergement* → *Accès SFTP & SSH*. Notez l'hôte (`access-XXXX.webspace-host.com`), l'utilisateur et le mot de passe.

2. **Envoyer les fichiers** (depuis ce dossier) :
   ```bash
   cd website-ia-insight
   sftp UTILISATEUR@access-XXXX.webspace-host.com
   # une fois connecté :
   put index.html
   put style.css
   put script.js
   put .htaccess
   put robots.txt
   ```
   (Dans FileZilla, activez *Serveur → Forcer l'affichage des fichiers cachés* pour voir le `.htaccess`.)
   Ou avec un client graphique (FileZilla, Cyberduck) : glisser les 3 fichiers à la racine du répertoire web (souvent `/` ou le dossier lié à votre domaine).

3. **Relier le domaine** : espace client IONOS → *Domaines & SSL* → votre domaine (ex. `ia-insight.fr`) → *Utiliser le domaine* → pointer vers le répertoire où vous avez déposé les fichiers.

4. **Activer le SSL** : IONOS inclut un certificat SSL — *Domaines & SSL* → activer HTTPS + redirection automatique HTTP → HTTPS.

## Option B — IONOS Deploy Now (Git, déploiement auto)

Si vous préférez déployer à chaque `git push` :

1. Créez un dépôt GitHub contenant le dossier `website-ia-insight/`.
2. Sur [ionos.space](https://ionos.space) (Deploy Now), connectez le dépôt.
3. Type de projet : **Static site**, dossier de publication : `website-ia-insight`.
4. Chaque push sur `main` redéploie automatiquement.

## Vérifications après mise en ligne

- [ ] `https://votre-domaine.fr` affiche le site en HTTPS
- [ ] `https://votre-domaine.fr/style.css/../` ne liste PAS les fichiers (le `.htaccess` répond 403/redirige)
- [ ] `https://votre-domaine.fr/DEPLOY-IONOS.md` renvoie une erreur (accès aux `.md` bloqué)
- [ ] Tester les en-têtes de sécurité sur [securityheaders.com](https://securityheaders.com) — note A attendue
- [ ] Le menu mobile fonctionne (tester sur téléphone)
- [ ] La bascule Mensuel/Annuel change les prix
- [ ] Les liens `mailto:` ouvrent bien un email vers `demo@ia-insightservices.fr` (démo) et `adv@ia-insightservices.fr` (contact/devis)
- [ ] Les boîtes `demo@` et `adv@ia-insightservices.fr` existent côté IONOS et sont relevées régulièrement
- [ ] Le lien téléphone `06 75 71 77 43` lance bien un appel sur mobile (`tel:+33675717743`)
- [ ] Ajouter le domaine dans Google Search Console (SEO)

## Personnalisation rapide

| Quoi | Où |
|---|---|
| Prix des offres | `index.html` — attributs `data-monthly` / `data-yearly` des `.price-num` |
| Email de contact | `index.html` — rechercher `mailto:` |
| Couleurs / identité | `style.css` — bloc `:root` (design tokens) |
| Textes marketing | `index.html` — chaque section est commentée (`<!-- ====== ... ====== -->`) |
