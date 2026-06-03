package io.multiagent.core.settings.repository;

import io.multiagent.core.model.ConsultantProfile;
import io.multiagent.core.model.SellerProfile;
import io.weaviate.client.WeaviateClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.multiagent.core.weaviate.WeaviateUtils.safeString;

@Slf4j
@Repository
public class SettingsWeaviateRepository {

    private final WeaviateClient client;
    private final String sellerProfileClassName;
    private final String consultantProfileClassName;

    public SettingsWeaviateRepository(
            WeaviateClient client,
            @Value("${weaviate.seller-profile-class:SellerProfile}") String sellerProfileClassName,
            @Value("${weaviate.consultant-profile-class:ConsultantProfile}") String consultantProfileClassName) {
        this.client = client;
        this.sellerProfileClassName = sellerProfileClassName;
        this.consultantProfileClassName = consultantProfileClassName;
    }

    public SellerProfile findSellerProfile(String companyName) {
        if (companyName == null || companyName.isBlank()) {
            return null;
        }
        try {
            var response = client.data().objectsGetter()
                    .withClassName(sellerProfileClassName)
                    .withLimit(100)
                    .run();
            if (response.hasErrors() || response.getResult() == null) {
                log.error("❌ findSellerProfile fetch error: {}", response.getError());
                return null;
            }
            return response.getResult().stream()
                    .filter(object -> object != null && object.getProperties() != null)
                    .map(object -> toSellerProfile(object.getProperties()))
                    .filter(profile -> profile != null && companyName.trim().equalsIgnoreCase(safeString(profile.companyName()).trim()))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.error("❌ findSellerProfile exception: {}", e.getMessage(), e);
            return null;
        }
    }

    public void upsertSellerProfile(SellerProfile profile) {
        try {
            String key  = safeString(profile.companyName()).toLowerCase().trim();
            String uuid = java.util.UUID.nameUUIDFromBytes(
                    key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();

            Map<String, Object> props = new HashMap<>();
            props.put("companyName",       safeString(profile.companyName()));
            props.put("address",           safeString(profile.address()));
            props.put("rcs",               safeString(profile.rcs()));
            props.put("iban",              safeString(profile.iban()));
            props.put("bic",               safeString(profile.bic()));
            props.put("email",             safeString(profile.email()));
            props.put("capital",           safeString(profile.capital()));
            props.put("latePaymentClause", safeString(profile.latePaymentClause()));

            client.data().deleter().withClassName(sellerProfileClassName).withID(uuid).run();

            var result = client.data().creator()
                    .withClassName(sellerProfileClassName)
                    .withProperties(props)
                    .withID(uuid)
                    .run();

            if (result.hasErrors()) {
                log.error("❌ upsertSellerProfile error: {}", result.getError());
                throw new RuntimeException("upsertSellerProfile failed: " + result.getError());
            }
            log.info("📥 Weaviate: SellerProfile upserted (companyName={})", profile.companyName());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("❌ upsertSellerProfile exception: {}", e.getMessage(), e);
            throw new RuntimeException("upsertSellerProfile exception: " + e.getMessage(), e);
        }
    }

    public void upsertConsultantProfile(ConsultantProfile profile) {
        try {
            String key  = safeString(profile.email()).toLowerCase().trim();
            String uuid = java.util.UUID.nameUUIDFromBytes(
                    key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();

            Map<String, Object> props = new HashMap<>();
            props.put("email",         safeString(profile.email()));
            props.put("name",          safeString(profile.name()));
            props.put("role",          safeString(profile.role()));
            props.put("company",       safeString(profile.company()));
            props.put("clientName",    safeString(profile.clientName()));
            props.put("clientAddress", safeString(profile.clientAddress()));
            props.put("clientRcs",     safeString(profile.clientRcs()));
            props.put("tjm",           profile.tjm() != null ? profile.tjm() : 0.0);
            props.put("active",        profile.active() != null ? profile.active() : Boolean.TRUE);

            client.data().deleter().withClassName(consultantProfileClassName).withID(uuid).run();

            var result = client.data().creator()
                    .withClassName(consultantProfileClassName)
                    .withProperties(props)
                    .withID(uuid)
                    .run();

            if (result.hasErrors()) {
                log.error("❌ Weaviate upsertConsultantProfile error: {}", result.getError());
                throw new RuntimeException("upsertConsultantProfile failed: " + result.getError());
            }
            log.info("📥 Weaviate: ConsultantProfile upserted (email={})", profile.email());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("❌ Exception upsertConsultantProfile: {}", e.getMessage(), e);
            throw new RuntimeException("upsertConsultantProfile exception: " + e.getMessage(), e);
        }
    }

    public List<ConsultantProfile> findAllConsultantProfiles(String company) {
        try {
            var response = client.data().objectsGetter()
                    .withClassName(consultantProfileClassName)
                    .withLimit(200)
                    .run();

            if (response.hasErrors() || response.getResult() == null) {
                log.error("❌ findAllConsultantProfiles fetch error: {}", response.getError());
                return List.of();
            }

            return response.getResult().stream()
                    .filter(object -> object != null && object.getProperties() != null)
                    .map(object -> toConsultantProfile(object.getProperties()))
                    .filter(p -> p != null)
                    .filter(p -> company == null || company.isBlank()
                            || company.trim().equalsIgnoreCase(safeString(p.company()).trim()))
                    .toList();
        } catch (Exception e) {
            log.error("❌ findAllConsultantProfiles exception: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public void deleteConsultantProfile(String email) {
        try {
            String key  = (email == null ? "" : email).toLowerCase().trim();
            String uuid = java.util.UUID.nameUUIDFromBytes(
                    key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();

            var result = client.data().deleter()
                    .withClassName(consultantProfileClassName)
                    .withID(uuid)
                    .run();

            if (result.hasErrors()) {
                log.error("❌ Weaviate deleteConsultantProfile error for email={}: {}", email, result.getError());
            } else {
                log.info("🗑️ Weaviate: ConsultantProfile supprimé (email={})", email);
            }
        } catch (Exception e) {
            log.error("❌ Exception deleteConsultantProfile: {}", e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private SellerProfile toSellerProfile(Map<String, Object> props) {
        if (props == null) {
            return null;
        }
        return new SellerProfile(
                safeString(props.get("companyName")),
                safeString(props.get("address")),
                safeString(props.get("rcs")),
                safeString(props.get("iban")),
                safeString(props.get("bic")),
                safeString(props.get("email")),
                safeString(props.get("capital")),
                safeString(props.get("latePaymentClause"))
        );
    }

    private ConsultantProfile toConsultantProfile(Map<String, Object> props) {
        if (props == null) {
            return null;
        }
        Double tjm = null;
        Object rawTjm = props.get("tjm");
        if (rawTjm instanceof Number n) {
            tjm = n.doubleValue();
        }
        Boolean active = Boolean.TRUE;
        Object rawActive = props.get("active");
        if (rawActive instanceof Boolean b) {
            active = b;
        } else if (rawActive != null) {
            active = Boolean.parseBoolean(rawActive.toString());
        }
        return new ConsultantProfile(
                safeString(props.get("email")),
                safeString(props.get("name")),
                safeString(props.get("role")),
                safeString(props.get("company")),
                safeString(props.get("clientName")),
                safeString(props.get("clientAddress")),
                safeString(props.get("clientRcs")),
                tjm,
                active
        );
    }
}
