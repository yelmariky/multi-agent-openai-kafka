# Fichiers cles

```
ai-core/src/main/java/io/multiagent/core/
  infrastructure/tenant/
    TenantContext.java              <- ThreadLocal tenant UUID + realm
    TenantFilter.java               <- JWT iss -> realm -> Organization -> TenantContext
    TenantAwareTaskDecorator.java   <- propage TenantContext aux threads async
  organization/
    entity/     Organization, Resource, Client, Mission, ResourceType
    repository/ OrganizationRepository, ResourceRepository, ClientRepository, MissionRepository
    service/    OrganizationService, ResourceService, MissionService
    service/keycloak/  KeycloakProvisioningService  <- provisionne realms Keycloak
    controller/ OrganizationController (/platform/tenants), TenantInfoController (/organization/me)
                ResourceController, ClientController, MissionController, CreateTenantRequest
  expense/
    entity/     ExpenseEntity (JPA + vector(3072))
    repository/ ExpenseJpaRepository (requetes native pgvector)
    controller/ ExpenseController, ExpenseApprovalController, ReceiptController
    service/    RAGService, OcrService, ExpenseReportService, ExpensePdfService, ExpenseExcelService...
  cra/
    entity/     CraEntity
    repository/ CraJpaRepository
    controller/ CraController
    service/    CraService
  settings/
    entity/     SellerProfileEntity, ConsultantProfileEntity
    repository/ SellerProfileJpaRepository, ConsultantProfileJpaRepository
    controller/ SettingsController, ConsultantController
  document/
    entity/     DocumentChunkEntity
    repository/ DocumentChunkJpaRepository
  notification/
    controller/ NotificationController (SSE admin), ConsultantNotificationController
    service/    NotificationService, ConsultantNotificationService
  reasoning/
    controller/ ReasoningController
    service/    ReasoningService, IntentClassifierService, SemanticSearchService, QueryRewriteService, ReRankService
  service/
    WeaviateService.java <- FACADE conservee, delegue aux JPA repos
  config/
    SecurityConfig.java   <- JwtIssuerAuthenticationManagerResolver multi-realm
    WebCorsConfig.java    <- CORS via WebMvcConfigurer

ai-core/src/main/resources/
  db/migration/V1__init_schema.sql  <- Flyway : toutes les tables + pgvector + seed ia-insight
  application.yml

invoice-service/src/main/java/io/multiagent/invoice/
  infrastructure/tenant/  TenantContext, TenantFilter, OrganizationEntity, OrganizationRepository
  entity/     InvoiceEntity, SellerProfileEntity (read-only depuis la table ai-core)
  repository/ InvoiceJpaRepository, SellerProfileJpaRepository
  controller/ InvoiceController
  service/    InvoiceService, DeleteInvoiceService

deploy/
  postgres/    deployment.yaml, service.yaml, pv.yaml, secret-template.yaml (pgvector/pgvector:pg16)
  keycloak/    install.sh, uninstall.sh, realm-configmap.yaml, theme-configmap.yaml
  kafka/       KRaft 3 noeuds
  gateway/     Kong DB-less

frontend/                   <- console admin — :3000/{slug}/
frontend-consultant/        <- espace consultant — :3001/{slug}/
frontend-platform/          <- console plateforme — :3002 (realm: platform)
```
