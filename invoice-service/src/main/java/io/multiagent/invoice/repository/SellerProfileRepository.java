package io.multiagent.invoice.repository;

import io.multiagent.invoice.model.SellerProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;

@Slf4j
@Repository
public class SellerProfileRepository {

    private final JdbcTemplate jdbc;

    public SellerProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SellerProfile findByCompanyName(String companyName) {
        if (companyName == null || companyName.isBlank()) {
            return null;
        }
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM seller_profiles WHERE company_id ILIKE ?",
                    (rs, rowNum) -> toSellerProfile(rs),
                    companyName.trim()
            );
        } catch (EmptyResultDataAccessException e) {
            return null;
        } catch (Exception e) {
            log.error("findSellerProfile exception: {}", e.getMessage(), e);
            return null;
        }
    }

    private SellerProfile toSellerProfile(ResultSet rs) throws SQLException {
        return new SellerProfile(
                rs.getString("company_id"),
                rs.getString("address"),
                rs.getString("rcs"),
                rs.getString("iban"),
                rs.getString("bic"),
                rs.getString("email"),
                rs.getString("capital"),
                rs.getString("late_payment_clause")
        );
    }
}
