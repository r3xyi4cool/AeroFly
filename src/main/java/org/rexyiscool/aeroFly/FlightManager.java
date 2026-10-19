package org.rexyiscool.aeroFly;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

public final class FlightManager implements Listener {

    private final AeroFly plugin;
    private final DatabaseManager databaseManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    // Cache
    private final Map<UUID, PlayerData> playerCache = new ConcurrentHashMap<>();
    private final List<Grant> activeGrants = new CopyOnWriteArrayList<>();
    private final Set<UUID> activeFlightPlayers = ConcurrentHashMap.newKeySet();

    // Configuration settings
    private ZoneId timezone = ZoneId.of("Asia/Kolkata");
    private LocalTime dailyResetTime = LocalTime.of(0, 0);
    private final List<Rank> ranks = new ArrayList<>();
    private Rank fallbackRank = new Rank("None", "", 0L, 0);
    private boolean chargeOnlyWhenAirborne = true;
    private final List<Long> warningThresholds = new ArrayList<>();
    private final Set<String> excludedWorlds = new HashSet<>();
    private String unlimitedText = "Unlimited";

    // Tasks
    private BukkitTask flightChargingTask;
    private BukkitTask autosaveTask;

    public FlightManager(AeroFly plugin, DatabaseManager databaseManager) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin cannot be null");
        this.databaseManager = Objects.requireNonNull(databaseManager, "DatabaseManager cannot be null");
    }

    public void initialize() {
        loadConfigValues();

        // Load active grants from database
        databaseManager.loadAllGrants().thenAccept(loadedGrants -> {
            activeGrants.clear();
            for (Grant g : loadedGrants) {
                if (!g.isExpired()) {
                    activeGrants.add(g);
                }
            }
            plugin.getLogger().info("Loaded " + activeGrants.size() + " active unlimited grants.");
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to load initial grants", ex);
            return null;
        });

        // Register events
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Start 20-tick (1-second) charging loop
        this.flightChargingTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickFlightCharging, 20L, 20L);

        // Start periodic autosave task
        int autosaveIntervalSeconds = plugin.getConfig().getInt("database.autosave-interval-seconds", 300);
        long autosaveTicks = Math.max(20L, autosaveIntervalSeconds * 20L);
        this.autosaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::runAutosave, autosaveTicks, autosaveTicks);
    }

    public synchronized void reload() {
        loadConfigValues();
    }

    private void loadConfigValues() {
        FileConfiguration config = plugin.getConfig();

        // Timezone
        String tzString = config.getString("timezone", "Asia/Kolkata");
        try {
            this.timezone = ZoneId.of(tzString);
        } catch (Exception e) {
            plugin.getLogger().warning("Invalid timezone '" + tzString + "'. Falling back to Asia/Kolkata.");
            this.timezone = ZoneId.of("Asia/Kolkata");
        }

        // Daily reset time (HH:mm)
        String resetTimeString = config.getString("daily-reset-time", "00:00");
        try {
            this.dailyResetTime = LocalTime.parse(resetTimeString, DateTimeFormatter.ofPattern("HH:mm"));
        } catch (DateTimeParseException e) {
            plugin.getLogger().warning("Invalid daily-reset-time '" + resetTimeString + "'. Falling back to 00:00.");
            this.dailyResetTime = LocalTime.of(0, 0);
        }

        // Ranks
        this.ranks.clear();
        ConfigurationSection ranksSection = config.getConfigurationSection("ranks");
        if (ranksSection != null) {
            for (String key : ranksSection.getKeys(false)) {
                ConfigurationSection sec = ranksSection.getConfigurationSection(key);
                if (sec != null) {
                    String permission = sec.getString("permission", "aerofly.rank." + key);
                    String timeStr = sec.getString("daily-time", "0s");
                    long seconds = 0L;
                    try {
                        seconds = DurationParser.parse(timeStr, true);
                    } catch (Exception ex) {
                        plugin.getLogger().warning("Rank '" + key + "' has invalid daily-time '" + timeStr + "': " + ex.getMessage());
                    }
                    int priority = sec.getInt("priority", 1);
                    ranks.add(new Rank(key, permission, seconds, priority));
                }
            }
        }
        // Sort ranks by priority descending
        Collections.sort(this.ranks);

        // Fallback rank
        String fbName = config.getString("fallback-rank.name", "None");
        String fbTime = config.getString("fallback-rank.daily-time", "0s");
        long fbSeconds = 0L;
        try {
            fbSeconds = DurationParser.parse(fbTime, true);
        } catch (Exception ignored) {}
        this.fallbackRank = new Rank(fbName, "", fbSeconds, 0);

        // Flight options
        this.chargeOnlyWhenAirborne = config.getBoolean("flight.charge-only-when-airborne", true);

        // Warning thresholds
        this.warningThresholds.clear();
        List<String> rawThresholds = config.getStringList("warning-thresholds");
        for (String raw : rawThresholds) {
            try {
                long sec = DurationParser.parse(raw, false);
                warningThresholds.add(sec);
            } catch (Exception ex) {
                plugin.getLogger().warning("Invalid warning threshold: " + raw);
            }
        }
        // Sort thresholds ascending
        Collections.sort(this.warningThresholds);

        // Excluded worlds
        this.excludedWorlds.clear();
        List<String> worlds = config.getStringList("excluded-worlds");
        for (String w : worlds) {
            excludedWorlds.add(w.toLowerCase());
        }

        this.unlimitedText = config.getString("unlimited-text", "Unlimited");
    }

    public LuckPerms getLuckPerms() {
        try {
            return LuckPermsProvider.get();
        } catch (IllegalStateException e) {
            var rsp = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
            return rsp != null ? rsp.getProvider() : null;
        }
    }

    /**
     * Calculates the monotonic reset period index for the given timestamp.
     * Compares epoch day corresponding to the reset cutoff time.
     */
    public long getResetPeriodIndex(ZonedDateTime zdt) {
        LocalDate date = zdt.toLocalTime().isBefore(dailyResetTime) ? zdt.toLocalDate().minusDays(1) : zdt.toLocalDate();
        return date.toEpochDay();
    }

    public long getCurrentResetPeriodIndex() {
        return getResetPeriodIndex(ZonedDateTime.now(timezone));
    }

    public Duration getTimeUntilNextReset() {
        ZonedDateTime now = ZonedDateTime.now(timezone);
        LocalDate date = now.toLocalTime().isBefore(dailyResetTime) ? now.toLocalDate().minusDays(1) : now.toLocalDate();
        ZonedDateTime nextReset = date.plusDays(1).atTime(dailyResetTime).atZone(timezone);
        return Duration.between(now, nextReset);
    }

    public Rank evaluatePlayerRank(Player player) {
        LuckPerms lp = getLuckPerms();
        if (lp != null) {
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                QueryOptions queryOptions = lp.getContextManager().getQueryOptions(player);
                CachedPermissionData permData = user.getCachedData().getPermissionData(queryOptions);

                for (Rank rank : ranks) {
                    if (permData.checkPermission(rank.getPermission()).asBoolean()) {
                        return rank;
                    }
                }
                return fallbackRank;
            }
        }

        // Fallback to Bukkit permission check
        for (Rank rank : ranks) {
            if (player.hasPermission(rank.getPermission())) {
                return rank;
            }
        }
        return fallbackRank;
    }

    public CompletableFuture<Rank> evaluateOfflineRank(UUID uuid) {
        LuckPerms lp = getLuckPerms();
        if (lp == null) {
            return CompletableFuture.completedFuture(fallbackRank);
        }

        return lp.getUserManager().loadUser(uuid).thenApply(user -> {
            if (user == null) {
                return fallbackRank;
            }
            QueryOptions queryOptions = lp.getContextManager().getStaticQueryOptions();
            CachedPermissionData permData = user.getCachedData().getPermissionData(queryOptions);

            for (Rank rank : ranks) {
                if (permData.checkPermission(rank.getPermission()).asBoolean()) {
                    return rank;
                }
            }
            return fallbackRank;
        }).exceptionally(ex -> fallbackRank);
    }

    public Set<String> getPlayerGroups(Player player) {
        Set<String> groups = new HashSet<>();
        LuckPerms lp = getLuckPerms();
        if (lp != null) {
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                QueryOptions queryOptions = lp.getContextManager().getQueryOptions(player);
                CachedPermissionData permData = user.getCachedData().getPermissionData(queryOptions);

                // LuckPerms group nodes are formatted as "group.<name>"
                lp.getGroupManager().getLoadedGroups().forEach(g -> {
                    String gName = g.getName().toLowerCase();
                    if (permData.checkPermission("group." + gName).asBoolean()) {
                        groups.add(gName);
                    }
                });
            }
        }
        return groups;
    }

    public CompletableFuture<Set<String>> getOfflinePlayerGroups(UUID uuid) {
        Set<String> groups = new HashSet<>();
        LuckPerms lp = getLuckPerms();
        if (lp == null) {
            return CompletableFuture.completedFuture(groups);
        }

        return lp.getUserManager().loadUser(uuid).thenApply(user -> {
            if (user != null) {
                QueryOptions queryOptions = lp.getContextManager().getStaticQueryOptions();
                CachedPermissionData permData = user.getCachedData().getPermissionData(queryOptions);

                lp.getGroupManager().getLoadedGroups().forEach(g -> {
                    String gName = g.getName().toLowerCase();
                    if (permData.checkPermission("group." + gName).asBoolean()) {
                        groups.add(gName);
                    }
                });
            }
            return groups;
        }).exceptionally(ex -> groups);
    }

    /**
     * Checks if a player has unlimited flight active (via bypass permission or unexpired grant).
     */
    public boolean hasUnlimitedFlight(Player player) {
        if (player.hasPermission("aerofly.bypass.time")) {
            return true;
        }

        Set<String> groups = getPlayerGroups(player);
        for (Grant grant : activeGrants) {
            if (grant.appliesTo(player.getUniqueId(), groups)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds all unexpired grants that apply to a player, sorted with latest expiry first.
     */
    public List<Grant> getApplicableGrants(UUID uuid, Set<String> groups) {
        List<Grant> matching = new ArrayList<>();
        for (Grant grant : activeGrants) {
            if (grant.appliesTo(uuid, groups)) {
                matching.add(grant);
            }
        }
        matching.sort((a, b) -> Long.compare(b.getExpiresAtMillis(), a.getExpiresAtMillis()));
        return matching;
    }

    public boolean isWorldExcluded(World world) {
        return world != null && excludedWorlds.contains(world.getName().toLowerCase());
    }

    public boolean isFlightActive(Player player) {
        return activeFlightPlayers.contains(player.getUniqueId());
    }

    public void enableFlight(Player player) {
        activeFlightPlayers.add(player.getUniqueId());
        player.setAllowFlight(true);
        player.setFlying(true);

        PlayerData data = playerCache.get(player.getUniqueId());
        if (data != null) {
            data.setLastChargeTimestamp(System.currentTimeMillis());
        }
    }

    public void disableFlight(Player player) {
        activeFlightPlayers.remove(player.getUniqueId());
        player.setFlying(false);
        player.setAllowFlight(false);
    }

    public PlayerData getPlayerData(UUID uuid) {
        return playerCache.get(uuid);
    }

    public void putPlayerData(PlayerData data) {
        playerCache.put(data.getUuid(), data);
    }

    public List<Grant> getActiveGrants() {
        return Collections.unmodifiableList(activeGrants);
    }

    public CompletableFuture<Grant> addGrant(Grant.GrantType type, String target, long durationSeconds, String createdBy) {
        long expiresAt = System.currentTimeMillis() + (durationSeconds * 1000L);
        return databaseManager.insertGrant(type, target, expiresAt, createdBy).thenApply(grant -> {
            activeGrants.add(grant);
            return grant;
        });
    }

    public CompletableFuture<Boolean> revokeGrant(int grantId) {
        return databaseManager.revokeGrant(grantId).thenApply(success -> {
            if (success) {
                activeGrants.removeIf(g -> g.getId() == grantId);
            }
            return success;
        });
    }

    /**
     * 1-Second tick loop to charge active flight time based on real timestamps.
     */
    private void tickFlightCharging() {
        long now = System.currentTimeMillis();
        long currentPeriod = getCurrentResetPeriodIndex();

        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = playerCache.get(player.getUniqueId());
            if (data == null) {
                continue;
            }

            // Check daily reset
            if (currentPeriod > data.getLastResetPeriod()) {
                data.resetDaily(currentPeriod);
                sendMessage(player, "daily-reset-notice");
            }

            // Excluded world check
            if (isWorldExcluded(player.getWorld())) {
                if (isFlightActive(player) || player.getAllowFlight()) {
                    disableFlight(player);
                    sendMessage(player, "flight-excluded-world",
                            Placeholder.unparsed("world", player.getWorld().getName()));
                }
                data.setLastChargeTimestamp(now);
                continue;
            }

            // Creative and Spectator modes never consume time
            GameMode gm = player.getGameMode();
            if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
                data.setLastChargeTimestamp(now);
                continue;
            }

            // Unlimited flight check
            if (hasUnlimitedFlight(player)) {
                data.setLastChargeTimestamp(now);
                continue;
            }

            // Check if player is flying and flight is active
            boolean flightActive = isFlightActive(player);
            boolean isAirborne = player.isFlying();
            boolean shouldCharge = flightActive && player.getAllowFlight() && (!chargeOnlyWhenAirborne || isAirborne);

            if (shouldCharge) {
                long lastCharge = data.getLastChargeTimestamp();
                if (lastCharge <= 0L) {
                    lastCharge = now;
                    data.setLastChargeTimestamp(now);
                }

                long elapsedMillis = now - lastCharge;
                long elapsedSeconds = elapsedMillis / 1000L;

                if (elapsedSeconds >= 1L) {
                    data.setLastChargeTimestamp(lastCharge + (elapsedSeconds * 1000L));

                    Rank rank = evaluatePlayerRank(player);
                    long remaining = data.drainTime(elapsedSeconds, rank.getDailyTimeSeconds());

                    if (remaining <= 0L) {
                        disableFlight(player);
                        sendMessage(player, "flight-expired");
                    } else {
                        // Check warning thresholds
                        for (long threshold : warningThresholds) {
                            if (remaining <= threshold) {
                                if (data.getNotifiedThresholds().add(threshold)) {
                                    sendMessage(player, "flight-warning",
                                            Placeholder.unparsed("time", DurationParser.format(remaining)));
                                }
                            }
                        }
                    }
                }
            } else {
                data.setLastChargeTimestamp(now);
            }
        }
    }

    private void runAutosave() {
        databaseManager.saveAllPlayers(playerCache.values(), false);
        databaseManager.purgeExpiredGrants();
        activeGrants.removeIf(Grant::isExpired);
    }

    // =========================================================================
    //                               Event Handlers
    // =========================================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }

        UUID uuid = event.getUniqueId();
        String name = event.getName();

        // Load into cache asynchronously on the pre-login thread
        try {
            PlayerData data = databaseManager.loadPlayerData(uuid, name).join();
            playerCache.put(uuid, data);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed pre-login data load for " + name, e);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        PlayerData data = playerCache.get(uuid);
        if (data == null) {
            // Fallback: load asynchronously if not ready from pre-login
            databaseManager.loadPlayerData(uuid, player.getName()).thenAccept(loaded -> {
                playerCache.put(uuid, loaded);
                loaded.setLastChargeTimestamp(System.currentTimeMillis());
                long currentPeriod = getCurrentResetPeriodIndex();
                if (currentPeriod > loaded.getLastResetPeriod()) {
                    loaded.resetDaily(currentPeriod);
                    sendMessage(player, "daily-reset-notice");
                }
            });
        } else {
            data.setLastName(player.getName());
            data.setLastChargeTimestamp(System.currentTimeMillis());
            long currentPeriod = getCurrentResetPeriodIndex();
            if (currentPeriod > data.getLastResetPeriod()) {
                data.resetDaily(currentPeriod);
                sendMessage(player, "daily-reset-notice");
            }
        }

        // Disable flight state if world is excluded
        if (isWorldExcluded(player.getWorld())) {
            if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
                player.setFlying(false);
                player.setAllowFlight(false);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        activeFlightPlayers.remove(uuid);
        PlayerData data = playerCache.remove(uuid);
        if (data != null) {
            // Save immediately asynchronously
            databaseManager.savePlayerData(data);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (isWorldExcluded(player.getWorld())) {
            if (isFlightActive(player) || (player.getAllowFlight() && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR)) {
                disableFlight(player);
                sendMessage(player, "flight-excluded-world",
                        Placeholder.unparsed("world", player.getWorld().getName()));
            }
        }
        PlayerData data = playerCache.get(player.getUniqueId());
        if (data != null) {
            data.setLastChargeTimestamp(System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        PlayerData data = playerCache.get(event.getPlayer().getUniqueId());
        if (data != null) {
            data.setLastChargeTimestamp(System.currentTimeMillis());
        }
    }

    // =========================================================================
    //                             Message Utilities
    // =========================================================================

    public void sendMessage(CommandSender sender, String messageKey, TagResolver... resolvers) {
        String raw = plugin.getConfig().getString("messages." + messageKey);
        if (raw == null || raw.isEmpty()) {
            return;
        }

        String prefix = plugin.getConfig().getString("messages.prefix", "");
        Component component = miniMessage.deserialize(prefix + raw, resolvers);
        sender.sendMessage(component);
    }

    public void sendRawMessage(CommandSender sender, String template, TagResolver... resolvers) {
        Component component = miniMessage.deserialize(template, resolvers);
        sender.sendMessage(component);
    }

    public String getUnlimitedText() {
        return unlimitedText;
    }

    public Rank getFallbackRank() {
        return fallbackRank;
    }

    public void shutdown() {
        if (flightChargingTask != null) {
            flightChargingTask.cancel();
        }
        if (autosaveTask != null) {
            autosaveTask.cancel();
        }

        // Disable flight for all active players on shutdown
        for (UUID uuid : activeFlightPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                if (p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) {
                    p.setFlying(false);
                    p.setAllowFlight(false);
                }
            }
        }
        activeFlightPlayers.clear();
    }
}
