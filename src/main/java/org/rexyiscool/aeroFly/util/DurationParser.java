package org.rexyiscool.aeroFly.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {

    public static final long MAX_SECONDS = 10L * 365L * 86400L; // 10 years cap
    private static final Pattern TOKEN_PATTERN = Pattern.compile("(\\d+)\\s*([a-zA-Z]+)");

    private DurationParser() {}

    /**
     * Parses duration strings like "30s", "15m", "2h", "1d", "2h30m".
     */
    public static long parse(String input, boolean allowZero) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("Duration cannot be empty.");
        }

        String trimmed = input.trim().toLowerCase();
        if (allowZero && isZeroPattern(trimmed)) {
            return 0L;
        }

        Matcher matcher = TOKEN_PATTERN.matcher(trimmed);
        long totalSeconds = 0L;
        int matchedEnd = 0;
        boolean foundAny = false;

        while (matcher.find()) {
            validateNoSkippedCharacters(trimmed, matchedEnd, matcher.start());
            foundAny = true;
            matchedEnd = matcher.end();

            long amount = parseAmount(matcher.group(1));
            long multiplier = parseUnitMultiplier(matcher.group(2));
            totalSeconds = addChecked(totalSeconds, amount, multiplier);
        }

        validateEndOfString(trimmed, matchedEnd, foundAny, input);
        if (!allowZero && totalSeconds <= 0L) {
            throw new IllegalArgumentException("Duration must be greater than zero.");
        }

        return totalSeconds;
    }

    private static boolean isZeroPattern(String text) {
        return text.equals("0s") || text.equals("0") || text.equals("0m") || text.equals("0h") || text.equals("0d");
    }

    private static void validateNoSkippedCharacters(String text, int prevEnd, int matchStart) {
        String skipped = text.substring(prevEnd, matchStart).trim();
        if (!skipped.isEmpty()) {
            throw new IllegalArgumentException("Unexpected characters in duration: '" + skipped + "'");
        }
    }

    private static long parseAmount(String numStr) {
        try {
            long amount = Long.parseLong(numStr);
            if (amount < 0) throw new IllegalArgumentException("Duration cannot be negative.");
            return amount;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Duration number is too large to parse.");
        }
    }

    private static long parseUnitMultiplier(String unit) {
        return switch (unit) {
            case "s", "sec", "second", "seconds" -> 1L;
            case "m", "min", "minute", "minutes" -> 60L;
            case "h", "hr", "hour", "hours" -> 3600L;
            case "d", "day", "days" -> 86400L;
            case "w", "week", "weeks" -> 7L * 86400L;
            default -> throw new IllegalArgumentException("Unknown time unit: '" + unit + "'. Valid units: s, m, h, d, w.");
        };
    }

    private static long addChecked(long current, long amount, long multiplier) {
        try {
            long part = Math.multiplyExact(amount, multiplier);
            long total = Math.addExact(current, part);
            if (total > MAX_SECONDS) {
                throw new IllegalArgumentException("Duration exceeds maximum limit of 10 years (" + MAX_SECONDS + "s).");
            }
            return total;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Duration exceeds the maximum allowed integer bounds.");
        }
    }

    private static void validateEndOfString(String text, int matchedEnd, boolean foundAny, String original) {
        String remaining = text.substring(matchedEnd).trim();
        if (!foundAny || !remaining.isEmpty()) {
            throw new IllegalArgumentException("Invalid duration format: '" + original + "'");
        }
    }

    /**
     * Formats seconds into a human-readable string (e.g. "2d 4h 30m 15s" or "0s").
     */
    public static String format(long totalSeconds) {
        if (totalSeconds <= 0L) {
            return "0s";
        }

        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (seconds > 0 || sb.length() == 0) sb.append(seconds).append("s");

        return sb.toString().trim();
    }
}
