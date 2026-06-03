package io.multiagent.core.weaviate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Locale;

/**
 * Static utility methods shared across all Weaviate repositories.
 */
public final class WeaviateUtils {

    private WeaviateUtils() {
        // utility class — not instantiable
    }

    public static String safeString(Object value) {
        return value == null ? "" : value.toString();
    }

    public static Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof Number number) {
                return number.intValue();
            }
            return Integer.parseInt(value.toString());
        } catch (Exception e) {
            return null;
        }
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

    public static Date toDate(String iso) {
        try {
            return Date.from(java.time.Instant.parse(iso));
        } catch (Exception e) {
            return null;
        }
    }

    public static LocalDate extractLocalDate(Object rawDate) {
        if (rawDate == null) {
            return null;
        }
        String value = rawDate.toString().trim();
        if (value.isBlank()) {
            return null;
        }
        try {
            if (value.length() >= 10 && Character.isDigit(value.charAt(0))) {
                return LocalDate.parse(value.substring(0, 10));
            }
        } catch (DateTimeParseException ignored) {
        }
        try {
            DateTimeFormatter english = DateTimeFormatter.ofPattern("MMMM d, yyyy, h:mm:ss a", Locale.ENGLISH);
            String normalized = value.replace('\u202f', ' ');
            return LocalDateTime.parse(normalized, english).toLocalDate();
        } catch (Exception ignored) {
        }
        return null;
    }

    public static YearMonth extractYearMonth(Object rawDate) {
        LocalDate parsed = extractLocalDate(rawDate);
        return parsed == null ? null : YearMonth.from(parsed);
    }

    public static Float[] toFloatArray(float[] values) {
        Float[] result = new Float[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i];
        }
        return result;
    }

    public static Float[] toFloatArray(java.util.List<Double> vector) {
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
