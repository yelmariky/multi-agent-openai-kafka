# Endpoints

## Notes de frais -- ai-core :8081

| Methode | Route | Role |
|---|---|---|
| POST | `/reasoning/analyze` | Pipeline complet : intent -> RAG -> extraction -> indexation. Body : `{"text":"...","consultantEmail":"..."}` |
| POST | `/receipts/upload` | OCR + extraction depuis fichier. Multipart : `file`, `paymentMode`, `consultantEmail` |
| GET | `/expenses/report` | JSON `?start=YYYY-MM-DD&end=YYYY-MM-DD[&consultantEmail=]` |
| GET | `/expenses/report/excel` | Export Excel `?start&end[&consultantEmail=]` |
| GET | `/expenses/report/pdf` | Export PDF `?start&end[&consultantEmail=]` |
| GET | `/expenses/report/pdf/month` | Export PDF mensuel `?month=YYYY-MM[&consultantEmail=]` |
| DELETE | `/expenses/delete` | Suppression par date |
| POST | `/expenses/approve` | `{"id":"...","note":"..."}` -> `approvalStatus = APPROVED` |
| POST | `/expenses/refuse` | `{"id":"...","note":"..."}` -> `approvalStatus = REFUSED` |

## CRA -- ai-core :8081

| Methode | Route | Role |
|---|---|---|
| POST | `/cra/save` | Upsert -- calcule `totalDays` a partir des entries. Statut defaut : `BROUILLON` |
| POST | `/cra/submit` | `BROUILLON` / `REFUSE` -> `SOUMIS` + push SSE `CRA_SUBMITTED` vers admin |
| POST | `/cra/validate` | `SOUMIS` -> `VALIDE`. Query param : `?validatedBy=Nom` |
| POST | `/cra/refuse` | `SOUMIS` -> `REFUSE`. Query param : `?reason=Motif`. Efface `submittedAt` |
| POST | `/cra/recall` | `SOUMIS` -> `BROUILLON`. Le consultant retire sa soumission avant decision admin |
| POST | `/cra/reopen` | `VALIDE` / `REFUSE` -> `SOUMIS`. L'admin annule sa decision |
| GET | `/cra/report` | `?start=YYYY-MM&end=YYYY-MM[&consultant=Nom][&company=]` |
| GET | `/cra/absences` | Fusion km + CRA ABSENT. `?month=YYYY-MM&consultant=Nom` |
| POST | `/cra/delete` | Body : `{"id":"<uuid>"}` |

## Notifications SSE -- ai-core :8081

| Methode | Route | Role |
|---|---|---|
| GET | `/admin/notifications/stream` | SSE admin. `?token=<jwt>`. Events : `init` + `notification` |
| GET | `/admin/notifications` | `?all=false` (non lus) ou `?all=true` |
| POST | `/admin/notifications/{id}/read` | Marque une notification comme lue |
| POST | `/admin/notifications/read-all` | Marque toutes comme lues |
| GET | `/consultant/notifications/stream` | SSE consultant. `?consultant=Nom&token=<jwt>` |
| GET | `/consultant/notifications` | `?consultant=Nom` |
| POST | `/consultant/notifications/{id}/read` | `?consultant=Nom` |
| POST | `/consultant/notifications/read-all` | `?consultant=Nom` |

## Organisation / Plateforme -- ai-core :8081

| Methode | Route | Role |
|---|---|---|
| GET | `/organization/me` | Tenant courant (any authenticated) |
| GET | `/platform/tenants` | Liste tenants (platform_admin) |
| POST | `/platform/tenants` | Cree tenant + provisionne realm Keycloak |
| PUT | `/platform/tenants/{id}` | Modifie tenant (nom, plan, actif) |
| DELETE | `/platform/tenants/{id}` | Desactive tenant |

## Parametres & Consultants -- ai-core :8081

| Methode | Route | Role |
|---|---|---|
| GET | `/settings/seller` | SellerProfile (IBAN, BIC, adresse...) |
| POST | `/settings/seller` | Upsert SellerProfile |
| GET | `/consultants/profiles` | Liste tous les profils consultants |
| POST | `/consultants/profiles` | Upsert un profil consultant |
| DELETE | `/consultants/profiles/{email}` | Supprime un profil consultant |

## Factures -- invoice-service :8083

| Methode | Route | Role |
|---|---|---|
| POST | `/invoices/generate` | Cree facture depuis `SimpleInvoiceRequest` -- upsert par invoiceName |
| POST | `/invoices/pdf` | Lookup DB -> re-telecharge le PDF |
| POST | `/invoices/excel` | Lookup DB -> re-telecharge le Excel |
| GET | `/invoices/report` | `?start=YYYY-MM&end=YYYY-MM&company=&consultantEmail=` |
| POST | `/invoices/delete` | Admin only. Body : `InvoiceLookupRequest { billingMonth, sellerCompanyName, invoiceName }` |
| POST | `/invoices/delete-by-text` | Suppression par texte libre (extraction LLM des criteres) |
