package org.rexyiscool.aeroFly.flight;

import com.earth2me.essentials.Essentials;
import net.ess3.api.IUser;
import net.ess3.api.events.FlyStatusChangeEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.query.QueryOptions;
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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.scheduler.BukkitTask;
import org.rexyiscool.aeroFly.AeroFly;
import org.rexyiscool.aeroFly.database.DatabaseManager;
import org.rexyiscool.aeroFly.model.Grant;
import org.rexyiscool.aeroFly.model.PlayerData;
import org.rexyiscool.aeroFly.model.Rank;
import org.rexyiscool.aeroFly.util.DurationParser;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
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
    private final Essentials essentials;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    // In-memory caches
    private final Map<UUID, PlayerData> playerCache = new ConcurrentHashMap<>();
    private final List<Grant> activeGrants = new CopyOnWriteArrayList<>();
    private final Set<UUID> activeFlightPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> fallProtectionPlayers = ConcurrentHashMap.newKeySet();

    // Configuration settings
    private ZoneId timezone = ZoneId.of("Asia/Kolkata");
    private LocalTime dailyResetTime = LocalTime.of(0, 0);
    private final List<Rank> ranks = new ArrayList<>();
    private Rank fallbackRank = new Rank("None", "", 0L, 0);
    private boolean chargeOnlyWhenAirborne = true;
    private boolean safeFallOnExpire = true;
    private final List<Long> warningThresholds = new ArrayList<>();
    private final Set<String> excludedWorlds = new HashSet<>();
    private String unlimitedText = "Unlimited";

    private BukkitTask flightChargingTask;
    private BukkitTask autosaveTask;

    public FlightManager(AeroFly plugin, DatabaseManager databaseManager, Essentials essentials) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin cannot be null");
        this.databaseManager = Objects.requireNonNull(databaseManager, "DatabaseManager cannot be null");
        this.essentials = Objects.requireNonNull(essentials, "Essentials cannot be null");
    }

    public void initialize() {
        loadConfigValues();
        loadGrantsFromDatabase();

        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Load data for already-connected players (in case of plugin reload)
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            databaseManager.loadPlayerData(onlinePlayer.getUniqueId(), onlinePlayer.getName()).thenAccept(loaded -> {
                playerCache.put(onlinePlayer.getUniqueId(), loaded);
                initJoinedPlayer(onlinePlayer, loaded);
            });
        }

        // 20 ticks (1s) flight charging loop
        this.flightChargingTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickFlightCharging, 20L, 20L);

        // Periodic database autosave task
        int autosaveSeconds = plugin.getConfig().getInt("database.autosave-interval-seconds", 300);
        long autosaveTicks = Math.max(20L, autosaveSeconds * 20L);
        this.autosaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::runAutosave, autosaveTicks, autosaveTicks);
    }

    public synchronized void reload() {
        loadConfigValues();
    }

    private void loadGrantsFromDatabase() {
        databaseManager.loadAllGrants().thenAccept(loaded -> {
            activeGrants.clear();
            for (Grant g : loaded) {
                if (!g.isExpired()) activeGrants.add(g);
            }
            plugin.getLogger().info("Loaded " + activeGrants.size() + " active unlimited grants.");
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to load grants", ex);
            return null;
        });
    }

    private void loadConfigValues() {
        FileConfiguration cfg = plugin.getConfig();
        loadTimezone(cfg);
        loadDailyResetTime(cfg);
        loadRanks(cfg);
        loadThresholds(cfg);

        this.chargeOnlyWhenAirborne = cfg.getBoolean("flight.charge-only-when-airborne", true);
        this.safeFallOnExpire = cfg.getBoolean("flight.safe-fall-on-expire", true);
        this.unlimitedText = cfg.getString("unlimited-text", "Unlimited");

        this.excludedWorlds.clear();
        for (String w : cfg.getStringList("excluded-worlds")) {
            excludedWorlds.add(w.toLowerCase());
        }
    }

    private void loadTimezone(FileConfiguration cfg) {
        String tz = cfg.getString("timezone", "Asia/Kolkata");
        try {
            this.timezone = ZoneId.of(tz);
        } catch (Exception e) {
            plugin.getLogger().warning("Invalid timezone '" + tz + "'. Defaulting to Asia/Kolkata.");
            this.timezone = ZoneId.of("Asia/Kolkata");
        }
    }

    private void loadDailyResetTime(FileConfiguration cfg) {
        String resetTime = cfg.getString("daily-reset-time", "00:00");
        try {
            this.dailyResetTime = LocalTime.parse(resetTime, DateTimeFormatter.ofPattern("HH:mm"));
        } catch (DateTimeParseException e) {
            plugin.getLogger().warning("Invalid daily-reset-time '" + resetTime + "'. Defaulting to 00:00.");
            this.dailyResetTime = LocalTime.of(0, 0);
        }
    }

    private void loadRanks(FileConfiguration cfg) {
        this.ranks.clear();
        ConfigurationSection sec = cfg.getConfigurationSection("ranks");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                ConfigurationSection rankSec = sec.getConfigurationSection(key);
                if (rankSec != null) parseRankEntry(key, rankSec);
            }
        }
        Collections.sort(this.ranks);

        String fbName = cfg.getString("fallback-rank.name", "None");
        String fbTime = cfg.getString("fallback-rank.daily-time", "0s");
        long fbSeconds = 0L;
        try {
            fbSeconds = DurationParser.parse(fbTime, true);
        } catch (Exception ignored) {}
        this.fallbackRank = new Rank(fbName, "", fbSeconds, 0);
    }

    private void parseRankEntry(String key, ConfigurationSection rankSec) {
        String perm = rankSec.getString("permission", "aerofly.rank." + key);
        String timeStr = rankSec.getString("daily-time", "0s");
        long seconds = 0L;
        try {
            seconds = DurationParser.parse(timeStr, true);
        } catch (Exception ex) {
            plugin.getLogger().warning("Rank '" + key + "' has invalid daily-time '" + timeStr + "'");
        }
        int priority = rankSec.getInt("priority", 1);
        ranks.add(new Rank(key, perm, seconds, priority));
    }

    private void loadThresholds(FileConfiguration cfg) {
        this.warningThresholds.clear();
        for (String raw : cfg.getStringList("warning-thresholds")) {
            try {
                warningThresholds.add(DurationParser.parse(raw, false));
            } catch (Exception ex) {
                plugin.getLogger().warning("Invalid warning threshold: " + raw);
            }
        }
        Collections.sort(this.warningThresholds);
    }

    public LuckPerms getLuckPerms() {
        try {
            return LuckPermsProvider.get();
        } catch (IllegalStateException e) {
            var rsp = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
            return rsp != null ? rsp.getProvider() : null;
        }
    }

    // Daily reset index: epoch day shifted by the daily reset time cutoff
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
            net.luckperms.api.model.user.User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                QueryOptions options = lp.getContextManager().getQueryOptions(player);
                CachedPermissionData data = user.getCachedData().getPermissionData(options);
                for (Rank r : ranks) {
                    if (data.checkPermission(r.getPermission()).asBoolean() || player.hasPermission(r.getPermission())) {
                        return r;
                    }
                }
                return fallbackRank;
            }
        }

        for (Rank r : ranks) {
            if (player.hasPermission(r.getPermission())) return r;
        }
        return fallbackRank;
    }

    public CompletableFuture<Rank> evaluateOfflineRank(UUID uuid) {
        LuckPerms lp = getLuckPerms();
        if (lp == null) return CompletableFuture.completedFuture(fallbackRank);

        return lp.getUserManager().loadUser(uuid).thenApply(user -> {
            if (user == null) return fallbackRank;
            CachedPermissionData data = user.getCachedData().getPermissionData(lp.getContextManager().getStaticQueryOptions());
            for (Rank r : ranks) {
                if (data.checkPermission(r.getPermission()).asBoolean()) return r;
            }
            return fallbackRank;
        }).exceptionally(ex -> fallbackRank);
    }

    public Set<String> getPlayerGroups(Player player) {
        Set<String> groups = new HashSet<>();
        LuckPerms lp = getLuckPerms();
        if (lp == null) return groups;

        net.luckperms.api.model.user.User user = lp.getUserManager().getUser(player.getUniqueId());
        if (user == null) return groups;

        QueryOptions options = lp.getContextManager().getQueryOptions(player);
        CachedPermissionData permData = user.getCachedData().getPermissionData(options);

        lp.getGroupManager().getLoadedGroups().forEach(g -> {
            String name = g.getName().toLowerCase();
            if (permData.checkPermission("group." + name).asBoolean()) groups.add(name);
        });
        return groups;
    }

    public CompletableFuture<Set<String>> getOfflinePlayerGroups(UUID uuid) {
        Set<String> groups = new HashSet<>();
        LuckPerms lp = getLuckPerms();
        if (lp == null) return CompletableFuture.completedFuture(groups);

        return lp.getUserManager().loadUser(uuid).thenApply(user -> {
            if (user != null) {
                CachedPermissionData permData = user.getCachedData().getPermissionData(lp.getContextManager().getStaticQueryOptions());
                lp.getGroupManager().getLoadedGroups().forEach(g -> {
                    String name = g.getName().toLowerCase();
                    if (permData.checkPermission("group." + name).asBoolean()) groups.add(name);
                });
            }
            return groups;
        }).exceptionally(ex -> groups);
    }

    public boolean hasUnlimitedFlight(Player player) {
        if (player.hasPermission("aerofly.bypass.time") ||
            player.hasPermission("essentials.fly.bypass") ||
            player.hasPermission("aerofly.admin")) {
            return true;
        }

        Set<String> groups = getPlayerGroups(player);
        for (Grant grant : activeGrants) {
            if (grant.appliesTo(player.getUniqueId(), groups)) return true;
        }
        return false;
    }

    public List<Grant> getApplicableGrants(UUID uuid, Set<String> groups) {
        List<Grant> matching = new ArrayList<>();
        for (Grant grant : activeGrants) {
            if (grant.appliesTo(uuid, groups)) matching.add(grant);
        }
        matching.sort((a, b) -> Long.compare(b.getExpiresAtMillis(), a.getExpiresAtMillis()));
        return matching;
    }

    public boolean isWorldExcluded(World world) {
        return world != null && excludedWorlds.contains(world.getName().toLowerCase());
    }

    public boolean isFlightActive(Player player) {
        if (player == null) return false;
        if (activeFlightPlayers.contains(player.getUniqueId())) return true;

        return player.getAllowFlight();
    }

    public boolean canFly(Player player) {
        if (hasUnlimitedFlight(player)) {
            return true;
        }

        PlayerData data = getOrCreatePlayerData(player);
        Rank rank = evaluatePlayerRank(player);
        long totalRemaining = data.getTotalRemaining(rank.getDailyTimeSeconds());
        return totalRemaining > 0L;
    }

    public void startTrackingFlight(Player player) {
        if (player == null) return;
        activeFlightPlayers.add(player.getUniqueId());
        fallProtectionPlayers.remove(player.getUniqueId());

        PlayerData data = getOrCreatePlayerData(player);
        data.setLastChargeTimestamp(System.currentTimeMillis());
    }

    public void stopTrackingFlight(Player player) {
        if (player == null) return;
        boolean wasTracked = activeFlightPlayers.remove(player.getUniqueId());

        PlayerData data = getPlayerData(player.getUniqueId());
        if (data != null && wasTracked) {
            long now = System.currentTimeMillis();
            if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR && !hasUnlimitedFlight(player)) {
                if (!chargeOnlyWhenAirborne || player.isFlying()) {
                    long lastCharge = data.getLastChargeTimestamp() <= 0L ? now : data.getLastChargeTimestamp();
                    long elapsedSeconds = (now - lastCharge) / 1000L;
                    if (elapsedSeconds > 0L) {
                        Rank rank = evaluatePlayerRank(player);
                        data.drainTime(elapsedSeconds, rank.getDailyTimeSeconds());
                    }
                }
            }
            data.setLastChargeTimestamp(now);
        }
    }

    /**
     * Turns flight off through Bukkit player flight state.
     *
     * @param player       The target player
     * @param isExpiration If true, indicates balance depletion (sends flight-expired message and safe-fall)
     */
    public void disableFlight(Player player, boolean isExpiration) {
        if (player == null) return;

        stopTrackingFlight(player);

        player.setFlying(false);
        player.setAllowFlight(false);
        player.setFallDistance(0f);

        if (isExpiration) {
            sendMessage(player, "flight-expired");
            if (safeFallOnExpire && !player.isOnGround()) {
                fallProtectionPlayers.add(player.getUniqueId());
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    fallProtectionPlayers.remove(player.getUniqueId());
                }, 300L);
            }
        }
    }

    public PlayerData getPlayerData(UUID uuid) {
        return playerCache.get(uuid);
    }

    public PlayerData getOrCreatePlayerData(Player player) {
        return playerCache.computeIfAbsent(player.getUniqueId(), u ->
                new PlayerData(u, player.getName(), 0L, getCurrentResetPeriodIndex(), 0L, System.currentTimeMillis())
        );
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
            if (success) activeGrants.removeIf(g -> g.getId() == grantId);
            return success;
        });
    }

    // 20 ticks (1s) flight charging loop
    private void tickFlightCharging() {
        long now = System.currentTimeMillis();
        long currentPeriod = getCurrentResetPeriodIndex();

        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = playerCache.get(player.getUniqueId());
            if (data == null) continue;

            processPlayerFlight(player, data, now, currentPeriod);
        }
    }

    private void processPlayerFlight(Player player, PlayerData data, long now, long currentPeriod) {
        // Daily reset check
        if (currentPeriod > data.getLastResetPeriod()) {
            data.resetDaily(currentPeriod);
            sendMessage(player, "daily-reset-notice");
        }

        // Excluded world check
        if (isWorldExcluded(player.getWorld())) {
            handleExcludedWorld(player, data, now);
            return;
        }

        // Creative/Spectator or Unlimited players consume no time
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR || hasUnlimitedFlight(player)) {
            data.setLastChargeTimestamp(now);
            return;
        }

        // Charge flight time if active
        boolean shouldCharge = isFlightActive(player) && (!chargeOnlyWhenAirborne || player.isFlying());
        if (shouldCharge) {
            chargeFlightTime(player, data, now);
        } else {
            data.setLastChargeTimestamp(now);
        }
    }

    private void handleExcludedWorld(Player player, PlayerData data, long now) {
        if (isFlightActive(player) || player.getAllowFlight()) {
            disableFlight(player, false);
            sendMessage(player, "flight-excluded-world", Placeholder.unparsed("world", player.getWorld().getName()));
        }
        data.setLastChargeTimestamp(now);
    }

    private void chargeFlightTime(Player player, PlayerData data, long now) {
        long lastCharge = data.getLastChargeTimestamp() <= 0L ? now : data.getLastChargeTimestamp();
        long elapsedSeconds = (now - lastCharge) / 1000L;

        if (elapsedSeconds < 1L) return;

        data.setLastChargeTimestamp(lastCharge + (elapsedSeconds * 1000L));
        Rank rank = evaluatePlayerRank(player);
        long remaining = data.drainTime(elapsedSeconds, rank.getDailyTimeSeconds());

        if (remaining <= 0L) {
            disableFlight(player, true);
        } else {
            checkWarningThresholds(player, data, remaining);
        }
    }

    private void checkWarningThresholds(Player player, PlayerData data, long remaining) {
        for (long threshold : warningThresholds) {
            if (remaining <= threshold && data.getNotifiedThresholds().add(threshold)) {
                sendMessage(player, "flight-warning", Placeholder.unparsed("time", DurationParser.format(remaining)));
            }
        }
    }

    private void runAutosave() {
        databaseManager.saveAllPlayers(playerCache.values(), false);
        databaseManager.purgeExpiredGrants();
        activeGrants.removeIf(Grant::isExpired);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;

        UUID uuid = event.getUniqueId();
        try {
            PlayerData data = databaseManager.loadPlayerData(uuid, event.getName()).join();
            playerCache.put(uuid, data);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Pre-login data load failed for " + event.getName(), e);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        PlayerData data = playerCache.get(uuid);
        if (data == null) {
            databaseManager.loadPlayerData(uuid, player.getName()).thenAccept(loaded -> {
                playerCache.put(uuid, loaded);
                initJoinedPlayer(player, loaded);
            });
        } else {
            data.setLastName(player.getName());
            initJoinedPlayer(player, data);
        }
    }

    private void initJoinedPlayer(Player player, PlayerData data) {
        data.setLastChargeTimestamp(System.currentTimeMillis());
        long currentPeriod = getCurrentResetPeriodIndex();
        if (currentPeriod > data.getLastResetPeriod()) {
            data.resetDaily(currentPeriod);
            sendMessage(player, "daily-reset-notice");
        }

        if (isWorldExcluded(player.getWorld())) {
            if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
                player.setFlying(false);
                player.setAllowFlight(false);
            }
            return;
        }

        // If player has fly enabled by Essentials or Bukkit but has no time left, disable it
        if (player.getAllowFlight()) {
            if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
                if (!canFly(player)) {
                    disableFlight(player, false);
                    sendMessage(player, "flight-no-time");
                } else {
                    startTrackingFlight(player);
                    player.setAllowFlight(true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        stopTrackingFlight(player);
        fallProtectionPlayers.remove(uuid);
        PlayerData data = playerCache.remove(uuid);
        if (data != null) {
            databaseManager.savePlayerData(data);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        stopTrackingFlight(player);

        if (isWorldExcluded(player.getWorld())) {
            disableFlight(player, false);
            sendMessage(player, "flight-excluded-world", Placeholder.unparsed("world", player.getWorld().getName()));
        } else {
            if (player.getAllowFlight()) {
                if (canFly(player)) {
                    startTrackingFlight(player);
                    player.setAllowFlight(true);
                } else {
                    disableFlight(player, false);
                    sendMessage(player, "flight-no-time");
                }
            }
        }

        PlayerData data = playerCache.get(player.getUniqueId());
        if (data != null) data.setLastChargeTimestamp(System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        GameMode newGm = event.getNewGameMode();

        if (newGm == GameMode.SURVIVAL || newGm == GameMode.ADVENTURE) {
            if (player.getAllowFlight() && canFly(player)) {
                startTrackingFlight(player);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline() && isFlightActive(player) && canFly(player)) {
                        player.setAllowFlight(true);
                    }
                });
            }
        }

        PlayerData data = playerCache.get(player.getUniqueId());
        if (data != null) data.setLastChargeTimestamp(System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (player.getAllowFlight()) {
            if (!isWorldExcluded(event.getRespawnLocation().getWorld()) && canFly(player)) {
                startTrackingFlight(player);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline() && isFlightActive(player)) {
                        player.setAllowFlight(true);
                    }
                });
            } else {
                disableFlight(player, false);
            }
        }
    }

    /**
     * Intercepts Essentials fly status toggling.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlyStatusChange(FlyStatusChangeEvent event) {
        IUser affected = event.getAffected();
        if (affected == null) return;
        Player player = affected.getBase();
        if (player == null || !player.isOnline()) return;

        boolean willEnable = event.getValue();

        if (willEnable) {
            if (isWorldExcluded(player.getWorld())) {
                event.setCancelled(true);
                sendMessage(player, "flight-excluded-world", Placeholder.unparsed("world", player.getWorld().getName()));
                return;
            }

            if (!canFly(player)) {
                event.setCancelled(true);
                sendMessage(player, "flight-no-time");
                IUser controller = event.getController();
                if (controller != null && controller.getBase() != null && !controller.getBase().equals(player)) {
                    sendMessage(controller.getBase(), "flight-no-time");
                }
                return;
            }

            startTrackingFlight(player);
        } else {
            stopTrackingFlight(player);
        }
    }

    /**
     * Safety net for flight started by other means in survival/adventure.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        if (event.isFlying()) {
            if (isWorldExcluded(player.getWorld())) {
                event.setCancelled(true);
                disableFlight(player, false);
                sendMessage(player, "flight-excluded-world", Placeholder.unparsed("world", player.getWorld().getName()));
                return;
            }

            if (!canFly(player)) {
                event.setCancelled(true);
                disableFlight(player, false);
                sendMessage(player, "flight-no-time");
                return;
            }

            startTrackingFlight(player);
        } else {
            if (chargeOnlyWhenAirborne) {
                PlayerData data = getPlayerData(player.getUniqueId());
                if (data != null && isFlightActive(player)) {
                    chargeFlightTime(player, data, System.currentTimeMillis());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        if (fallProtectionPlayers.remove(player.getUniqueId())) {
            event.setCancelled(true);
            player.setFallDistance(0f);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.isOnGround() && fallProtectionPlayers.remove(player.getUniqueId())) {
            player.setFallDistance(0f);
        }
    }

    public void sendMessage(CommandSender sender, String messageKey, TagResolver... resolvers) {
        String raw = plugin.getConfig().getString("messages." + messageKey);
        if (raw == null || raw.isEmpty()) return;

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

    public int getRankCount() {
        return ranks.size();
    }

    public List<Rank> getRanks() {
        return Collections.unmodifiableList(ranks);
    }

    public void shutdown() {
        if (flightChargingTask != null) flightChargingTask.cancel();
        if (autosaveTask != null) autosaveTask.cancel();

        for (UUID uuid : activeFlightPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                stopTrackingFlight(p);
            }
        }
        activeFlightPlayers.clear();
        fallProtectionPlayers.clear();
    }
}
