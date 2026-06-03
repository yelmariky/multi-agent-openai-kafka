package io.multiagent.invoice.util;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * Static utility methods shared across all Weaviate repositories in invoice-service.
 */
public final class WeaviateUtils {

    private WeaviateUtils() {
        // utility class — not instantiable
    }

    public static String safeString(Object value) {
        return value == null ? "" : value.toString();
    }

    public static Double parseDouble(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            return Double.parseDouble(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    public static BigDecimal parseBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof Number number) {
                return BigDecimal.valueOf(number.doubleValue());
            }
            return new BigDecimal(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    public static LocalDate parseLocalDateValue(Object value) {
        if (value == null) {
            return null;
        }
        String raw = value.toString().trim();
        if (raw.isBlank()) {
            return null;
        }
        try {
            if (raw.length() >= 10 && Character.isDigit(raw.charAt(0))) {
                return LocalDate.parse(raw.substring(0, 10));
            }
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(raw, DateTimeFormatter.ISO_DATE_TIME).toLocalDate();
        } catch (Exception ignored) {
        }
        try {
            DateTimeFormatter english = DateTimeFormatter.ofPattern("MMMM d, yyyy, h:mm:ss a", Locale.ENGLISH);
            String normalized = raw.replace('\u202f', ' ');
            return LocalDateTime.parse(normalized, english).toLocalDate();
        } catch (Exception ignored) {
        }
        return null;
    }

    public static boolean isNullOrBlank(String s) {
        return s == null || s.isBlank();
    }

    public static String formatRfc3339(LocalDate date) {
        return date.atTime(LocalTime.MIDNIGHT)
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
    }

    public static Float[] toFloatArray(List<Double> vector) {
        if (vector == null) {
            return new Float[0];
        }
        Float[] result = new Float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            Double value = vector.get(i);
            result[i] = value == null ? 0f : value.floatValue();
        }
        return result;
    }
}
