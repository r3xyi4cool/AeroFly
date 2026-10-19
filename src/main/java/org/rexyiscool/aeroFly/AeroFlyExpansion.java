package org.rexyiscool.aeroFly;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Objects;


public final class AeroFlyExpansion extends PlaceholderExpansion {

    private final AeroFly plugin;
    private final FlightManager flightManager;

    public AeroFlyExpansion(AeroFly plugin, FlightManager flightManager) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin cannot be null");
        this.flightManager = Objects.requireNonNull(flightManager, "FlightManager cannot be null");
    }

    @Override
    public @NotNull String getIdentifier() {
        return "aerofly";
    }

    @Override
    public @NotNull String getAuthor() {
        List<String> authors = plugin.getDescription().getAuthors();
        return (authors != null && !authors.isEmpty()) ? String.join(", ", authors) : "rexyiscool";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer offlinePlayer, @NotNull String params) {
        if (params.equalsIgnoreCase("next_reset")) {
            Duration duration = flightManager.getTimeUntilNextReset();
            return DurationParser.format(Math.max(0L, duration.getSeconds()));
        }

        if (offlinePlayer == null || offlinePlayer.getUniqueId() == null) {
            return "0s";
        }

        Player onlinePlayer = offlinePlayer.getPlayer();
        PlayerData data = flightManager.getPlayerData(offlinePlayer.getUniqueId());

        // Placeholders must read strictly from the in-memory cache without blocking on SQLite
        Rank rank = onlinePlayer != null ? flightManager.evaluatePlayerRank(onlinePlayer) : flightManager.getFallbackRank();
        long dailyAllowance = rank.getDailyTimeSeconds();
        long dailyRemaining = data != null ? data.getDailyRemaining(dailyAllowance) : 0L;
        long purchasedSeconds = data != null ? data.getPurchasedSeconds() : 0L;
        long totalFinite = dailyRemaining + purchasedSeconds;
        boolean hasUnlimited = (onlinePlayer != null) && flightManager.hasUnlimitedFlight(onlinePlayer);

        switch (params.toLowerCase()) {
            case "daily_remaining":
                return DurationParser.format(dailyRemaining);

            case "daily_remaining_seconds":
                return String.valueOf(dailyRemaining);

            case "daily_allowance":
                return DurationParser.format(dailyAllowance);

            case "purchased_time":
                return DurationParser.format(purchasedSeconds);

            case "purchased_seconds":
                return String.valueOf(purchasedSeconds);

            case "total_available":
                if (hasUnlimited) {
                    return flightManager.getUnlimitedText();
                }
                return DurationParser.format(totalFinite);

            case "total_available_seconds":
                if (hasUnlimited) {
                    return "-1";
                }
                return String.valueOf(totalFinite);

            case "is_flying":
                if (onlinePlayer != null) {
                    return String.valueOf(flightManager.isFlightActive(onlinePlayer) && onlinePlayer.getAllowFlight());
                }
                return "false";

            case "has_unlimited":
                return String.valueOf(hasUnlimited);

            case "rank":
                return rank.getName();

            default:
                return null;
        }
    }
}
