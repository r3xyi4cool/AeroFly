package org.rexyiscool.aeroFly;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class FlyCommand implements CommandExecutor, TabCompleter {

    private final AeroFly plugin;
    private final FlightManager flightManager;

    public FlyCommand(AeroFly plugin, FlightManager flightManager) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin cannot be null");
        this.flightManager = Objects.requireNonNull(flightManager, "FlightManager cannot be null");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            flightManager.sendMessage(sender, "player-only");
            return true;
        }

        if (!player.hasPermission("aerofly.use")) {
            flightManager.sendMessage(player, "no-permission");
            return true;
        }

        // Check if world is excluded
        if (flightManager.isWorldExcluded(player.getWorld())) {
            flightManager.sendMessage(player, "flight-excluded-world",
                    Placeholder.unparsed("world", player.getWorld().getName()));
            return true;
        }

        // Toggle flight
        boolean currentlyActive = flightManager.isFlightActive(player) && player.getAllowFlight();

        if (currentlyActive) {
            flightManager.disableFlight(player);
            flightManager.sendMessage(player, "flight-disabled");
            return true;
        }

        // Check if player has flight allowance or unlimited
        boolean hasUnlimited = flightManager.hasUnlimitedFlight(player);
        if (!hasUnlimited) {
            PlayerData data = flightManager.getPlayerData(player.getUniqueId());
            Rank rank = flightManager.evaluatePlayerRank(player);
            long totalRemaining = data != null ? data.getTotalRemaining(rank.getDailyTimeSeconds()) : 0L;

            if (totalRemaining <= 0L) {
                flightManager.sendMessage(player, "flight-no-time");
                return true;
            }
        }

        flightManager.enableFlight(player);
        flightManager.sendMessage(player, "flight-enabled");
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return Collections.emptyList();
    }
}
