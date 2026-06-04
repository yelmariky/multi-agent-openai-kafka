package io.multiagent.notefrais.settings;

import io.multiagent.notefrais.model.ConsultantProfile;
import io.multiagent.notefrais.model.SellerProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class SettingsWeaviateRepository {

    private final JdbcTemplate jdbc;

    // -----------------------------------------------------------------------
    // SellerProfile
    // -----------------------------------------------------------------------

    public SellerProfile findSellerProfile(String companyName) {
        if (companyName == null || companyName.isBlank()) return null;
        try {
            List<SellerProfile> rows = jdbc.query(
                "SELECT * FROM seller_profiles WHERE company_id ILIKE ?",
                (rs, n) -> new SellerProfile(
                    rs.getString("company_id"),
                    rs.getString("address"),
                    rs.getString("rcs"),
                    rs.getString("iban"),
                    rs.getString("bic"),
                    rs.getString("email"),
                    rs.getString("capital"),
                    rs.getString("late_payment_clause")),
                companyName.trim());
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.error("findSellerProfile exception: {}", e.getMessage(), e);
            return null;
        }
    }

    public void upsertSellerProfile(SellerProfile profile) {
        try {
            jdbc.update("""
                INSERT INTO seller_profiles (company_id, address, rcs, iban, bic, email, capital, late_payment_clause)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (company_id) DO UPDATE SET
                    address = EXCLUDED.address, rcs = EXCLUDED.rcs, iban = EXCLUDED.iban,
                    bic = EXCLUDED.bic, email = EXCLUDED.email, capital = EXCLUDED.capital,
                    late_payment_clause = EXCLUDED.late_payment_clause, updated_at = now()
                """,
                safe(profile.companyName()), safe(profile.address()), safe(profile.rcs()),
                safe(profile.iban()), safe(profile.bic()), safe(profile.email()),
                safe(profile.capital()), safe(profile.latePaymentClause()));
            log.info("SellerProfile upserted (companyName={})", profile.companyName());
        } catch (Exception e) {
            log.error("upsertSellerProfile exception: {}", e.getMessage(), e);
            throw new RuntimeException("upsertSellerProfile exception: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // ConsultantProfile
    // -----------------------------------------------------------------------

    public void upsertConsultantProfile(ConsultantProfile profile) {
        try {
            jdbc.update("""
                INSERT INTO consultant_profiles (company_id, email, name, role, client_name, client_address, client_rcs, tjm, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (company_id, email) DO UPDATE SET
                    name = EXCLUDED.name, role = EXCLUDED.role,
                    client_name = EXCLUDED.client_name, client_address = EXCLUDED.client_address,
                    client_rcs = EXCLUDED.client_rcs, tjm = EXCLUDED.tjm,
                    active = EXCLUDED.active, updated_at = now()
                """,
                safe(profile.company()), safe(profile.email()), safe(profile.name()),
                safe(profile.role()), safe(profile.clientName()), safe(profile.clientAddress()),
                safe(profile.clientRcs()),
                profile.tjm(),
                profile.active() != null ? profile.active() : Boolean.TRUE);
            log.info("ConsultantProfile upserted (email={})", profile.email());
        } catch (Exception e) {
            log.error("upsertConsultantProfile exception: {}", e.getMessage(), e);
            throw new RuntimeException("upsertConsultantProfile exception: " + e.getMessage(), e);
        }
    }

    public List<ConsultantProfile> findAllConsultantProfiles(String company) {
        try {
            if (company == null || company.isBlank()) {
                return jdbc.query(
                    "SELECT * FROM consultant_profiles ORDER BY name",
                    (rs, n) -> mapConsultant(rs));
            }
            return jdbc.query(
                "SELECT * FROM consultant_profiles WHERE company_id ILIKE ? ORDER BY name",
                (rs, n) -> mapConsultant(rs), company.trim());
        } catch (Exception e) {
            log.error("findAllConsultantProfiles exception: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public void deleteConsultantProfile(String email) {
        try {
            int deleted = jdbc.update("DELETE FROM consultant_profiles WHERE email ILIKE ?",
                email != null ? email.trim() : "");
            log.info("ConsultantProfile deleted (email={}, rows={})", email, deleted);
        } catch (Exception e) {
            log.error("deleteConsultantProfile exception: {}", e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ConsultantProfile mapConsultant(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ConsultantProfile(
            rs.getString("email"),
            rs.getString("name"),
            rs.getString("role"),
            rs.getString("company_id"),
            rs.getString("client_name"),
            rs.getString("client_address"),
            rs.getString("client_rcs"),
            rs.getObject("tjm") != null ? rs.getDouble("tjm") : null,
            rs.getObject("active") != null ? rs.getBoolean("active") : Boolean.TRUE);
    }

    private static String safe(String s) {
        return s != null ? s : "";
    }
}
