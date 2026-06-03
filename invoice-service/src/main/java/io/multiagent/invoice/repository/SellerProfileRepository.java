package io.multiagent.invoice.repository;

import io.multiagent.invoice.model.SellerProfile;
import io.weaviate.client.WeaviateClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import static io.multiagent.invoice.util.WeaviateUtils.safeString;

@Slf4j
@Repository
public class SellerProfileRepository {

    private final WeaviateClient client;
    private final String sellerProfileClassName;

    public SellerProfileRepository(
            WeaviateClient client,
            @Value("${weaviate.seller-profile-class:SellerProfile}") String sellerProfileClassName) {
        this.client = client;
        this.sellerProfileClassName = sellerProfileClassName;
    }

    public SellerProfile findByCompanyName(String companyName) {
        if (companyName == null || companyName.isBlank()) {
            return null;
        }
        try {
            var response = client.data().objectsGetter()
                    .withClassName(sellerProfileClassName)
                    .withLimit(100)
                    .run();
            if (response.hasErrors() || response.getResult() == null) {
                log.error("findSellerProfile fetch error: {}", response.getError());
                return null;
            }
            return response.getResult().stream()
                    .filter(object -> object != null && object.getProperties() != null)
                    .map(object -> toSellerProfile(object.getProperties()))
                    .filter(profile -> profile != null
                            && companyName.trim().equalsIgnoreCase(safeString(profile.companyName()).trim()))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.error("findSellerProfile exception: {}", e.getMessage(), e);
            return null;
        }
    }

    private SellerProfile toSellerProfile(java.util.Map<String, Object> props) {
        if (props == null) return null;
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
}
