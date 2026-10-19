package org.rexyiscool.aeroFly;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {

    public static final long MAX_SECONDS = 10L * 365L * 86400L; // 10 years in seconds
    private static final Pattern TOKEN_PATTERN = Pattern.compile("(\\d+)\\s*([a-zA-Z]+)");

    private DurationParser() {}

    /**
     * Parses a duration string (e.g., "30s", "15m", "2h", "1d", "2h30m").
     *
     * @param input the input string
     * @param allowZero whether 0s is accepted
     * @return duration in seconds
     * @throws IllegalArgumentException if malformed, negative, zero (when disallowed), or exceeding 10 years
     */
    public static long parse(String input, boolean allowZero) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("Duration cannot be empty.");
        }

        String trimmed = input.trim().toLowerCase();

        if (allowZero && (trimmed.equals("0s") || trimmed.equals("0") || trimmed.equals("0m") || trimmed.equals("0h") || trimmed.equals("0d"))) {
            return 0L;
        }

        Matcher matcher = TOKEN_PATTERN.matcher(trimmed);
        long totalSeconds = 0L;
        int matchedEnd = 0;
        boolean foundAny = false;

        while (matcher.find()) {
            // Ensure no rogue non-whitespace characters between tokens
            String skipped = trimmed.substring(matchedEnd, matcher.start()).trim();
            if (!skipped.isEmpty()) {
                throw new IllegalArgumentException("Unexpected characters in duration: '" + skipped + "'");
            }

            foundAny = true;
            matchedEnd = matcher.end();

            long amount;
            try {
                amount = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Duration number is too large to parse.");
            }

            if (amount < 0) {
                throw new IllegalArgumentException("Duration cannot be negative.");
            }

            String unit = matcher.group(2);
            long unitMultiplier;

            switch (unit) {
                case "s":
                case "sec":
                case "second":
                case "seconds":
                    unitMultiplier = 1L;
                    break;
                case "m":
                case "min":
                case "minute":
                case "minutes":
                    unitMultiplier = 60L;
                    break;
                case "h":
                case "hr":
                case "hour":
                case "hours":
                    unitMultiplier = 3600L;
                    break;
                case "d":
                case "day":
                case "days":
                    unitMultiplier = 86400L;
                    break;
                case "w":
                case "week":
                case "weeks":
                    unitMultiplier = 7L * 86400L;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown time unit: '" + unit + "'. Valid units: s, m, h, d, w.");
            }

            try {
                long part = Math.multiplyExact(amount, unitMultiplier);
                totalSeconds = Math.addExact(totalSeconds, part);
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("Duration exceeds the maximum allowed integer bounds.");
            }

            if (totalSeconds > MAX_SECONDS) {
                throw new IllegalArgumentException("Duration exceeds maximum limit of 10 years (" + MAX_SECONDS + "s).");
            }
        }

        String remaining = trimmed.substring(matchedEnd).trim();
        if (!foundAny || !remaining.isEmpty()) {
            throw new IllegalArgumentException("Invalid duration format: '" + input + "'");
        }

        if (!allowZero && totalSeconds <= 0L) {
            throw new IllegalArgumentException("Duration must be greater than zero.");
        }

        return totalSeconds;
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
