package org.rexyiscool.aeroFly.hook;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rexyiscool.aeroFly.AeroFly;
import org.rexyiscool.aeroFly.flight.FlightManager;
import org.rexyiscool.aeroFly.model.PlayerData;
import org.rexyiscool.aeroFly.model.Rank;
import org.rexyiscool.aeroFly.util.DurationParser;

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

        Rank rank = onlinePlayer != null ? flightManager.evaluatePlayerRank(onlinePlayer) : flightManager.getFallbackRank();
        long dailyAllowance = rank.getDailyTimeSeconds();
        long dailyRemaining = data != null ? data.getDailyRemaining(dailyAllowance) : 0L;
        long purchasedSeconds = data != null ? data.getPurchasedSeconds() : 0L;
        long totalFinite = dailyRemaining + purchasedSeconds;
        boolean hasUnlimited = (onlinePlayer != null) && flightManager.hasUnlimitedFlight(onlinePlayer);

        return switch (params.toLowerCase()) {
            case "daily_remaining" -> DurationParser.format(dailyRemaining);
            case "daily_remaining_seconds" -> String.valueOf(dailyRemaining);
            case "daily_allowance" -> DurationParser.format(dailyAllowance);
            case "purchased_time" -> DurationParser.format(purchasedSeconds);
            case "purchased_seconds" -> String.valueOf(purchasedSeconds);
            case "total_available" -> hasUnlimited ? flightManager.getUnlimitedText() : DurationParser.format(totalFinite);
            case "total_available_seconds" -> hasUnlimited ? "-1" : String.valueOf(totalFinite);
            case "is_flying" -> String.valueOf(onlinePlayer != null && flightManager.isFlightActive(onlinePlayer) && onlinePlayer.getAllowFlight());
            case "has_unlimited" -> String.valueOf(hasUnlimited);
            case "rank" -> rank.getName();
            default -> null;
        };
    }
}
