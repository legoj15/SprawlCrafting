package com.legoj15.solver;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * The one number format every tree uses for item counts in the gather list, its tooltips, the plan
 * preview and the progress toast. Lives here (Minecraft-free, Java 8) because this library is the one
 * piece every build depends on and bundles.
 *
 * <p>Scheme ({@link #format}): plain digits grouped with commas below 10,000 ({@code 9}, {@code 1,234});
 * then three significant digits with a short-scale suffix, trailing zeros dropped ({@code 12.3K},
 * {@code 4.56M}, {@code 7.89B}, {@code 1.23T}, {@code 4.56Q}, {@code 10K}); from 10<sup>18</sup> up,
 * scientific ({@code 9.22e18}). {@link #compact} is the same scheme squeezed to at most four characters
 * for a 16-pixel item-slot overlay ({@code 999}, {@code 1.2K}, {@code 12K}, {@code 9e18}); the one
 * exception is a saturated count, which gets a fifth character ({@code 9e18+}, see below).
 *
 * <p>Locale-safe by construction: it never touches {@code String.format} / {@code NumberFormat}, so a
 * player whose JVM runs in a locale with other separators or digits (German, Arabic, Turkish) sees
 * the same text as everyone else, and a server-built string reads the same on every client.
 *
 * <p>The solver saturates counts at {@link Long#MAX_VALUE} rather than wrapping; such a count means
 * "at least this many" ({@link #isSaturated}). Callers with room show it through their "more than %s"
 * translation key with {@code format(Long.MAX_VALUE)} as the argument; {@link #compact}, which has no
 * room for words, marks it with a trailing {@code +} ({@code 9e18+}) so it never reads as an exact value.
 */
public final class CountFormat {

    private static final long[] UNITS = {1_000L, 1_000_000L, 1_000_000_000L, 1_000_000_000_000L,
            1_000_000_000_000_000L};
    private static final String[] SUFFIXES = {"K", "M", "B", "T", "Q"};
    /** First value written with a suffix; below it the grouped plain form is still short. */
    private static final long SUFFIX_FROM = 10_000L;
    /** First value written in scientific notation (1000Q). */
    private static final long SCIENTIFIC_FROM = 1_000_000_000_000_000_000L;
    private static final MathContext THREE = new MathContext(3, RoundingMode.HALF_UP);
    private static final MathContext TWO = new MathContext(2, RoundingMode.HALF_UP);

    private CountFormat() {
    }

    /** True when {@code count} is the solver's saturation marker (the true figure is at least this). */
    public static boolean isSaturated(long count) {
        return count == Long.MAX_VALUE;
    }

    /** Clamps a count to the int range, for wire formats and APIs that only carry an int. */
    public static int clampToInt(long count) {
        return count >= Integer.MAX_VALUE ? Integer.MAX_VALUE : count <= Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) count;
    }

    /** Readable count: {@code 1,234}, {@code 12.3K}, {@code 4.56M}, {@code 9.22e18}. */
    public static String format(long count) {
        if (count < 0) {
            return "-" + (count == Long.MIN_VALUE ? format(Long.MAX_VALUE) : format(-count));
        }
        if (count < SUFFIX_FROM) {
            return grouped(count);
        }
        if (count >= SCIENTIFIC_FROM) {
            return scientific(count, THREE);
        }
        return suffixed(count, THREE);
    }

    /**
     * At most four characters, for an item-slot overlay: {@code 999}, {@code 1.2K}, {@code 12K},
     * {@code 9e18}. A saturated count ({@link #isSaturated}) is "at least this many", so it gets a
     * trailing {@code +} ({@code 9e18+}, five characters) rather than reading as an exact figure.
     */
    public static String compact(long count) {
        if (count < 0) {
            return "-" + compactMagnitude(count == Long.MIN_VALUE ? Long.MAX_VALUE : -count);
        }
        return isSaturated(count) ? compactMagnitude(count) + "+" : compactMagnitude(count);
    }

    private static String compactMagnitude(long count) {
        if (count < UNITS[0]) {
            return Long.toString(count);
        }
        if (count >= SCIENTIFIC_FROM) {
            return scientific(count, new MathContext(1, RoundingMode.HALF_UP));
        }
        return suffixed(count, null);
    }

    private static String grouped(long count) {
        String digits = Long.toString(count);
        StringBuilder out = new StringBuilder(digits.length() + digits.length() / 3);
        int lead = digits.length() % 3 == 0 ? 3 : digits.length() % 3;
        out.append(digits, 0, lead);
        for (int i = lead; i < digits.length(); i += 3) {
            out.append(',').append(digits, i, i + 3);
        }
        return out.toString();
    }

    /**
     * {@code count} (>= 1000) scaled to the largest unit it reaches. {@code precision} null means the
     * compact rule: two significant digits under 10 of the unit ({@code 1.2K}), whole units above.
     * A value that rounds up to 1000 of its unit moves to the next one ({@code 999,950 -> 1M}).
     */
    private static String suffixed(long count, MathContext precision) {
        int unit = UNITS.length - 1;
        while (unit > 0 && count < UNITS[unit]) {
            unit--;
        }
        BigDecimal scaled = BigDecimal.valueOf(count).divide(BigDecimal.valueOf(UNITS[unit]));
        BigDecimal rounded = round(scaled, precision);
        if (rounded.compareTo(BigDecimal.valueOf(1000)) >= 0) {
            if (unit == UNITS.length - 1) {
                return scientific(count, precision == null ? new MathContext(1, RoundingMode.HALF_UP) : precision);
            }
            unit++;
            rounded = round(BigDecimal.valueOf(count).divide(BigDecimal.valueOf(UNITS[unit])), precision);
        }
        return plain(rounded) + SUFFIXES[unit];
    }

    private static BigDecimal round(BigDecimal scaled, MathContext precision) {
        if (precision != null) {
            return scaled.round(precision);
        }
        return scaled.compareTo(BigDecimal.TEN) < 0
                ? scaled.round(TWO)
                : scaled.setScale(0, RoundingMode.HALF_UP);
    }

    /** {@code 9.22e18}: mantissa at {@code precision} significant digits, lower-case {@code e}. */
    private static String scientific(long count, MathContext precision) {
        BigDecimal rounded = BigDecimal.valueOf(count).round(precision);
        int exponent = rounded.precision() - rounded.scale() - 1;
        BigDecimal mantissa = rounded.movePointLeft(exponent);
        return plain(mantissa) + "e" + exponent;
    }

    private static String plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }
}
