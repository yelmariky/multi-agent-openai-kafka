# Base de donnees

PostgreSQL 16 + pgvector. Base unique `ia_insight`. ai-core possede le schema via Flyway (`flyway.enabled=true`), invoice-service lit/ecrit mais ne gere pas les migrations (`flyway.enabled=false`).

Tables : `organization`, `resource`, `client`, `mission`, `seller_profile`, `consultant_profile`, `expense`, `cra`, `invoice`, `document_chunk`, `notification`. Toutes ont `tenant_id UUID NOT NULL REFERENCES organization(id)` sauf `organization`.

## Requetes pgvector avec tenant_id

```java
@Query(value = "SELECT * FROM expense WHERE tenant_id = :tenantId " +
       "ORDER BY embedding <=> CAST(:queryVector AS vector) LIMIT :limit",
       nativeQuery = true)
List<ExpenseEntity> findSimilar(@Param("tenantId") UUID tenantId,
                                @Param("queryVector") String queryVector,
                                @Param("limit") int limit);
```

## Flyway

- ai-core : `flyway.enabled=true`, migrations dans `db/migration/V1__init_schema.sql`
- invoice-service : `flyway.enabled=false` (shared DB)
- Pour ajouter une migration : creer `V2__description.sql` dans ai-core uniquement

## WeaviateService facade

`WeaviateService.java` est conserve comme facade de compatibilite. Delegue aux JPA repositories. Ne pas creer de nouvelles dependances vers cette classe -- utiliser les repos JPA directement.

## Notifications SSE -- store in-memory

- `NotificationService` (admin) : `CopyOnWriteArrayList`, perdu au redemarrage
- `ConsultantNotificationService` : `Map<String, List<SseEmitter>>` keyed par nom consultant
- Broken pipe SSE : log silence dans `application.yml` -- ce n'est pas un bug applicatif
- Frontends reconnectent `EventSource` toutes les 5s

## Backward compatibility consultantEmail sur Invoice

Les factures indexees avant l'ajout du champ `consultantEmail` ont ce champ vide.
`findInvoicesByPeriod` les inclut quand meme pour ne pas casser la liste admin.
