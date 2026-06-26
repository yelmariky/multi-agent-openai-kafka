# Regles metier

## paymentMode
- "compte business", "carte business", "carte societe" = `Business` (PRIORITE ABSOLUE)
- Mention societe seule = `Personnel`
- Sans indication = `Personnel` par defaut

## Frais km mensuels
- Declenches quand le LLM met `"monthly": true` dans le JSON extrait
- Java ne fait aucune detection par regex ou nom de mois -- c'est entierement pilote par le prompt
- `RAGService.expandKmMonthly()` genere les lignes journalieres en excluant :
  weekends + jours feries francais fixes + `absencePeriods`
- `copyExpense()` propage **tous les champs**, dont `consultantEmail`

## Jours feries (codes dans `RAGService.frenchFixedHolidays()`)
1/1, 1/5, 8/5, 14/7, 15/8, 1/11, 11/11, 25/12

## Workflow CRA (machine d'etat)

```
         +-----------------------------+
         |                             |
         v                             |
   [BROUILLON] --/cra/submit--> [SOUMIS] --/cra/validate--> [VALIDE]
         ^                       |    ^                          |
         |                       |    +------/cra/reopen---------+
         +--/cra/refuse<---------+    +------/cra/reopen---> [SOUMIS]
         +--/cra/recall (consultant annule avant decision admin)
```

| Etat | Consultant | Admin |
|---|---|---|
| BROUILLON | Editable, peut soumettre | Visible en lecture |
| SOUMIS | Lecture seule, peut rappeler (`/cra/recall`) | Peut valider ou refuser |
| VALIDE | Lecture seule | Peut annuler (`/cra/reopen` -> SOUMIS) |
| REFUSE | Badge rouge + motif, grille re-editable | Peut annuler (`/cra/reopen` -> SOUMIS) |

Effets de bord :
- `/cra/submit` -> notification SSE `CRA_SUBMITTED` vers admin
- `/cra/refuse` cote admin frontend -> appel auto `deleteInvoiceForCra()` (supprime la facture associee)

## Modele CraRequest -- 12 champs

```json
{
  "id", "consultant", "company", "clientCompany", "billingMonth",
  "entries", "totalDays", "status", "submittedAt", "validatedAt",
  "validatedBy", "refusedReason"
}
```

> Tout `new CraRequest(...)` doit avoir exactement **12 arguments**. `refusedReason` est le 12eme.

Types d'entree `CraDayEntry` : `TRAVAIL 1.0` (journee), `TRAVAIL 0.5` (demi-journee), `ABSENT 0.0`, `FERIE 0.0`, `WEEKEND 0.0`.
`CraService.save()` recalcule `totalDays` a chaque upsert.

## CRA -- absences et demi-journees
- `/cra/absences` fusionne : `absencePeriodsJson` (frais km) + jours `ABSENT` du CRA
- Demi-journees (`TRAVAIL 0.5`) = 0.5j d'absence dans le calendrier, mais NON exclues des frais km
- Absences auto-chargees a l'entree dans l'onglet Notes de frais (frontend consultant)

## Invoice generation -- convention nommage

```
billingMonth = 2026-05
-> invoiceDate = 2026-07-01  (billingMonth + 2 mois)
-> invoiceName = F-202607-01  (YYYYMM de invoiceDate + sequence 01)
```

Upsert : cherche facture existante par `invoiceName + sellerCompanyName + tenantId`, supprime si trouvee, puis sauvegarde la nouvelle.
