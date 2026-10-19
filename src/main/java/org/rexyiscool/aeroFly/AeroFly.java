package org.rexyiscool.aeroFly;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public final class AeroFly extends JavaPlugin {

    private DatabaseManager databaseManager;
    private FlightManager flightManager;
    private AeroFlyExpansion expansion;

    @Override
    public void onEnable() {
        // Save default config.yml if it doesn't exist
        saveDefaultConfig();

        // Initialize SQLite Database
        String dbName = getConfig().getString("database.filename", "aerofly.db");
        this.databaseManager = new DatabaseManager(this, dbName);
        try {
            this.databaseManager.initialize();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Could not initialize SQLite database! Disabling AeroFly...", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize Flight Manager & Listeners
        this.flightManager = new FlightManager(this, databaseManager);
        this.flightManager.initialize();

        // Register Commands
        PluginCommand flyCommand = getCommand("fly");
        if (flyCommand != null) {
            FlyCommand flyExecutor = new FlyCommand(this, flightManager);
            flyCommand.setExecutor(flyExecutor);
            flyCommand.setTabCompleter(flyExecutor);
        } else {
            getLogger().warning("Command /fly was not found in plugin.yml!");
        }

        PluginCommand aeroFlyCommand = getCommand("aerofly");
        if (aeroFlyCommand != null) {
            AeroFlyCommand aeroFlyExecutor = new AeroFlyCommand(this, flightManager, databaseManager);
            aeroFlyCommand.setExecutor(aeroFlyExecutor);
            aeroFlyCommand.setTabCompleter(aeroFlyExecutor);
        } else {
            getLogger().warning("Command /aerofly was not found in plugin.yml!");
        }

        // Optional PlaceholderAPI Hook
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null && getConfig().getBoolean("placeholderapi.enabled", true)) {
            try {
                Class.forName("me.clip.placeholderapi.expansion.PlaceholderExpansion");
                this.expansion = new AeroFlyExpansion(this, flightManager);
                this.expansion.register();
                getLogger().info("Successfully hooked into PlaceholderAPI.");
            } catch (Throwable t) {
                getLogger().warning("PlaceholderAPI found but could not initialize expansion: " + t.getMessage());
            }
        }

        getLogger().info("AeroFly v" + getDescription().getVersion() + " has been successfully enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabling AeroFly...");

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
                if (data != null) {
                    onlineData.add(data);
                }
            }
            flightManager.shutdown();
        }

        if (databaseManager != null) {
            databaseManager.close(onlineData);
        }

        getLogger().info("AeroFly has been disabled.");
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
}
