package org.rexyiscool.aeroFly;

import com.earth2me.essentials.Essentials;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.rexyiscool.aeroFly.command.AeroFlyCommand;
import org.rexyiscool.aeroFly.database.DatabaseManager;
import org.rexyiscool.aeroFly.flight.FlightManager;
import org.rexyiscool.aeroFly.hook.AeroFlyExpansion;
import org.rexyiscool.aeroFly.model.PlayerData;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class AeroFly extends JavaPlugin {

    // Configurable Discord link
    private static final String DISCORD_LINK = "https://discord.gg/aerofly";

    // Color palette (MiniMessage hex tags)
    private static final String BLUE_GREEN = "<#0096c7>";
    private static final String TURQUOISE_SURF = "<#00b4d8>";
    private static final String FROSTED_BLUE = "<#90e0ef>";

    // ASCII art banner
    private static final String[] ASCII_LOGO = {
        "    _                    _____ _",
        "   / \\   ___ _ __ ___   |  ___| |_   _",
        "  / _ \\ / _ \\ '__/ _ \\  | |_  | | | | |",
        " / ___ \\  __/ | | (_) | |  _| | | |_| |",
        "/_/   \\_\\___|_|  \\___/  |_|   |_|\\__, |",
        "                                  |___/"
    };

    private DatabaseManager databaseManager;
    private FlightManager flightManager;
    private AeroFlyExpansion expansion;
    private Essentials essentials;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!initializeDatabase()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getComponentLogger().info("SQLite database initialized successfully");

        if (!hookLuckPerms()) {
            getComponentLogger().error("LuckPerms not found or failed to hook! Disabling AeroFly...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getComponentLogger().info("LuckPerms hooked successfully");

        if (!hookEssentials()) {
            getLogger().severe("Essentials not found or failed to hook! AeroFly requires EssentialsX as a host plugin. Disabling plugin...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getComponentLogger().info("Essentials hooked successfully");

        this.flightManager = new FlightManager(this, databaseManager, essentials);
        this.flightManager.initialize();

        registerCommands();
        registerPlaceholderHook();

        getComponentLogger().info("Loaded " + flightManager.getRankCount() + " ranks from config");

        if (getConfig().getBoolean("startup-banner", true)) {
            logStartupBanner();
        }
    }

    private boolean initializeDatabase() {
        String dbName = getConfig().getString("database.filename", "aerofly.db");
        this.databaseManager = new DatabaseManager(this, dbName);
        try {
            this.databaseManager.initialize();
            return true;
        } catch (SQLException e) {
            getComponentLogger().error("Could not initialize SQLite database! Disabling AeroFly...", e);
            return false;
        }
    }

    private boolean hookLuckPerms() {
        try {
            if (getServer().getPluginManager().getPlugin("LuckPerms") == null) {
                return false;
            }
            try {
                return LuckPermsProvider.get() != null;
            } catch (IllegalStateException ignored) {
                RegisteredServiceProvider<LuckPerms> rsp = getServer().getServicesManager().getRegistration(LuckPerms.class);
                return rsp != null && rsp.getProvider() != null;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean hookEssentials() {
        try {
            Plugin plugin = getServer().getPluginManager().getPlugin("Essentials");
            if (plugin instanceof Essentials ess && plugin.isEnabled()) {
                this.essentials = ess;
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private void registerCommands() {
        PluginCommand aeroFlyCommand = getCommand("aerofly");
        if (aeroFlyCommand != null) {
            AeroFlyCommand aeroFlyExecutor = new AeroFlyCommand(this, flightManager, databaseManager);
            aeroFlyCommand.setExecutor(aeroFlyExecutor);
            aeroFlyCommand.setTabCompleter(aeroFlyExecutor);
        } else {
            getComponentLogger().warn("Command /aerofly was not found in plugin.yml!");
        }
    }

    private void registerPlaceholderHook() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null || !getConfig().getBoolean("placeholderapi.enabled", true)) {
            getComponentLogger().warn("PlaceholderAPI not found, placeholders disabled");
            return;
        }

        try {
            Class.forName("me.clip.placeholderapi.expansion.PlaceholderExpansion");
            this.expansion = new AeroFlyExpansion(this, flightManager);
            this.expansion.register();
            getComponentLogger().info("PlaceholderAPI expansion registered");
        } catch (Throwable t) {
            getComponentLogger().warn("PlaceholderAPI found but could not initialize expansion: " + t.getMessage());
        }
    }

    private void logStartupBanner() {
        ComponentLogger logger = getComponentLogger();
        MiniMessage mm = MiniMessage.miniMessage();

        logger.info(Component.empty());
        for (String line : ASCII_LOGO) {
            logger.info(mm.deserialize(BLUE_GREEN + line));
        }
        logger.info(Component.empty());
        logger.info(mm.deserialize(TURQUOISE_SURF + "The plugin successfully started."));
        logger.info(mm.deserialize(FROSTED_BLUE + "AeroFly v" + getPluginMeta().getVersion() + " (Paper " + Bukkit.getVersion() + ", MC " + Bukkit.getMinecraftVersion() + ")"));
        logger.info(mm.deserialize(FROSTED_BLUE + "Author: " + String.join(", ", getPluginMeta().getAuthors())));
        logger.info(mm.deserialize(TURQUOISE_SURF + "Discord: " + DISCORD_LINK));
        logger.info(Component.empty());
    }

    @Override
    public void onDisable() {
        getComponentLogger().info("Disabling AeroFly...");

        if (expansion != null) {
            try {
                expansion.unregister();
            } catch (Throwable ignored) {}
            expansion = null;
        }

        List<PlayerData> onlineData = new ArrayList<>();
        if (flightManager != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                PlayerData data = flightManager.getPlayerData(player.getUniqueId());
                if (data != null) onlineData.add(data);
            }
            flightManager.shutdown();
        }

        if (databaseManager != null) {
            databaseManager.close(onlineData);
        }

        getComponentLogger().info("AeroFly has been disabled.");
    }

    public void reloadPluginConfig() {
        reloadConfig();
        if (flightManager != null) {
            flightManager.reload();
        }
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public FlightManager getFlightManager() {
        return flightManager;
    }

    public Essentials getEssentials() {
        return essentials;
    }
}
