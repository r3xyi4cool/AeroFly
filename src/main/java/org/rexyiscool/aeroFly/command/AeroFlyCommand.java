package org.rexyiscool.aeroFly.command;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.luckperms.api.LuckPerms;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rexyiscool.aeroFly.AeroFly;
import org.rexyiscool.aeroFly.database.DatabaseManager;
import org.rexyiscool.aeroFly.flight.FlightManager;
import org.rexyiscool.aeroFly.model.Grant;
import org.rexyiscool.aeroFly.model.PlayerData;
import org.rexyiscool.aeroFly.model.Rank;
import org.rexyiscool.aeroFly.util.DurationParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class AeroFlyCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_ADMIN = "aerofly.admin";
    private static final String PERM_ADD = "aerofly.admin.add";
    private static final String PERM_REMOVE = "aerofly.admin.remove";
    private static final String PERM_SET = "aerofly.admin.set";
    private static final String PERM_INFINITE = "aerofly.admin.infinite";
    private static final String PERM_REVOKE = "aerofly.admin.revoke";
    private static final String PERM_CHECK = "aerofly.admin.check";
    private static final String PERM_RELOAD = "aerofly.admin.reload";

    private static final List<String> DURATION_SUGGESTIONS = List.of("30s", "15m", "1h", "2h", "1d", "7d");

    private final AeroFly plugin;
    private final FlightManager flightManager;
    private final DatabaseManager databaseManager;

    public AeroFlyCommand(AeroFly plugin, FlightManager flightManager, DatabaseManager databaseManager) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin cannot be null");
        this.flightManager = Objects.requireNonNull(flightManager, "FlightManager cannot be null");
        this.databaseManager = Objects.requireNonNull(databaseManager, "DatabaseManager cannot be null");
    }

    private record ResolvedPlayer(UUID uuid, String name, boolean isOnline) {}

    @FunctionalInterface
    private interface BalanceAction {
        long apply(PlayerData data, long seconds);
    }

    private CompletableFuture<ResolvedPlayer> resolvePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(new ResolvedPlayer(online.getUniqueId(), online.getName(), true));
        }

        CompletableFuture<ResolvedPlayer> future = new CompletableFuture<>();
        LuckPerms lp = flightManager.getLuckPerms();
        if (lp != null) {
            lp.getUserManager().lookupUniqueId(name).thenAccept(uuid -> {
                if (uuid != null) {
                    future.complete(new ResolvedPlayer(uuid, name, false));
                } else {
                    resolveFromBukkit(name, future);
                }
            }).exceptionally(ex -> {
                resolveFromBukkit(name, future);
                return null;
            });
        } else {
            resolveFromBukkit(name, future);
        }
        return future;
    }

    private void resolveFromBukkit(String name, CompletableFuture<ResolvedPlayer> future) {
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && cached.hasPlayedBefore()) {
            future.complete(new ResolvedPlayer(cached.getUniqueId(), cached.getName() != null ? cached.getName() : name, false));
            return;
        }

        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() != null && op.getName().equalsIgnoreCase(name) && op.hasPlayedBefore()) {
                future.complete(new ResolvedPlayer(op.getUniqueId(), op.getName(), false));
                return;
            }
        }
        future.complete(null);
    }

    private boolean hasPermission(CommandSender sender, String perm) {
        if (!(sender instanceof Player)) return true;
        return sender.hasPermission(perm) || sender.hasPermission(PERM_ADMIN);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "add" -> handleAdd(sender, args);
            case "remove" -> handleRemove(sender, args);
            case "set" -> handleSet(sender, args);
            case "infinite" -> handleInfinite(sender, args);
            case "grants" -> handleGrants(sender);
            case "revoke" -> handleRevoke(sender, args);
            case "check" -> handleCheck(sender, args);
            case "reload" -> handleReload(sender);
            default -> sendHelp(sender, label);
        }
        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        flightManager.sendRawMessage(sender, "<gradient:#00c6ff:#0072ff><bold>======= AeroFly Commands =======</bold></gradient>");
        if (sender.hasPermission("essentials.fly")) {
            flightManager.sendRawMessage(sender, "<gray>/fly <dark_gray>- <white>Toggle your flight mode (EssentialsX)</white></gray>");
        }
        if (hasPermission(sender, PERM_ADD)) {
            flightManager.sendRawMessage(sender, "<gray>/" + label + " add <player> <time> <dark_gray>- <white>Add purchased flight time</white></gray>");
        }
        if (hasPermission(sender, PERM_REMOVE)) {
            flightManager.sendRawMessage(sender, "<gray>/" + label + " remove <player> <time> <dark_gray>- <white>Deduct purchased flight time</white></gray>");
        }
        if (hasPermission(sender, PERM_SET)) {
            flightManager.sendRawMessage(sender, "<gray>/" + label + " set <player> <time> <dark_gray>- <white>Set purchased balance (use 0s for zero)</white></gray>");
        }
        if (hasPermission(sender, PERM_INFINITE)) {
            flightManager.sendRawMessage(sender, "<gray>/" + label + " infinite all <time> <dark_gray>- <white>Grant temporary unlimited flight globally</white></gray>");
            flightManager.sendRawMessage(sender, "<gray>/" + label + " infinite player <player> <time> <dark_gray>- <white>Grant temporary unlimited flight to player</white></gray>");
            flightManager.sendRawMessage(sender, "<gray>/" + label + " infinite group <group> <time> <dark_gray>- <white>Grant temporary unlimited flight to group</white></gray>");
            flightManager.sendRawMessage(sender, "<gray>/" + label + " grants <dark_gray>- <white>List all active unlimited grants</white></gray>");
        }
        if (hasPermission(sender, PERM_REVOKE)) {
            flightManager.sendRawMessage(sender, "<gray>/" + label + " revoke <id> <dark_gray>- <white>Revoke an active grant by ID</white></gray>");
        }
        if (hasPermission(sender, PERM_CHECK)) {
            flightManager.sendRawMessage(sender, "<gray>/" + label + " check <player> <dark_gray>- <white>Inspect player balance and grants</white></gray>");
        }
        if (hasPermission(sender, PERM_RELOAD)) {
            flightManager.sendRawMessage(sender, "<gray>/" + label + " reload <dark_gray>- <white>Reload configuration and ranks</white></gray>");
        }
        flightManager.sendRawMessage(sender, "<gradient:#00c6ff:#0072ff><bold>=================================</bold></gradient>");
    }

    // Consolidated balance management handler (eliminates duplication in add, remove, and set)
    private void handleBalanceCommand(CommandSender sender, String[] args, String perm, String action,
                                      boolean allowZero, BalanceAction op, String msgKey) {
        if (!hasPermission(sender, perm)) {
            flightManager.sendMessage(sender, "no-permission");
            return;
        }
        if (args.length < 3) {
            flightManager.sendRawMessage(sender, "<red>Usage: /aerofly " + action.toLowerCase() + " <player> <duration></red>");
            return;
        }

        long seconds;
        try {
            seconds = DurationParser.parse(args[2], allowZero);
        } catch (IllegalArgumentException e) {
            flightManager.sendRawMessage(sender, "<red>" + e.getMessage() + "</red>");
            return;
        }

        String targetName = args[1];
        resolvePlayer(targetName).thenAccept(resolved -> {
            if (resolved == null) {
                runOnMain(() -> flightManager.sendMessage(sender, "player-not-found", Placeholder.unparsed("player", targetName)));
                return;
            }
            applyBalanceModification(sender, resolved, action, seconds, op, msgKey);
        });
    }

    private void applyBalanceModification(CommandSender sender, ResolvedPlayer resolved, String action,
                                          long seconds, BalanceAction op, String msgKey) {
        if (resolved.isOnline()) {
            PlayerData data = flightManager.getPlayerData(resolved.uuid());
            if (data != null) {
                long newBalance = op.apply(data, seconds);
                databaseManager.savePlayerData(data);
                notifyBalanceSuccess(sender, action, resolved, seconds, newBalance, msgKey);
                return;
            }
        }

        databaseManager.modifyPlayerDataAsync(resolved.uuid(), resolved.name(), data -> op.apply(data, seconds))
                .thenAccept(updated -> notifyBalanceSuccess(sender, action, resolved, seconds, updated.getPurchasedSeconds(), msgKey));
    }

    private void notifyBalanceSuccess(CommandSender sender, String action, ResolvedPlayer resolved,
                                      long seconds, long newBalance, String msgKey) {
        logAdminAction(sender, action, resolved.name(), resolved.uuid(), DurationParser.format(seconds), DurationParser.format(newBalance));
        runOnMain(() -> flightManager.sendMessage(sender, msgKey,
                Placeholder.unparsed("amount", DurationParser.format(seconds)),
                Placeholder.unparsed("player", resolved.name()),
                Placeholder.unparsed("balance", DurationParser.format(newBalance))));
    }

    private void handleAdd(CommandSender sender, String[] args) {
        handleBalanceCommand(sender, args, PERM_ADD, "ADD", false,
                (data, sec) -> data.addPurchased(sec, DurationParser.MAX_SECONDS), "add-success");
    }

    private void handleRemove(CommandSender sender, String[] args) {
        handleBalanceCommand(sender, args, PERM_REMOVE, "REMOVE", false,
                PlayerData::removePurchased, "remove-success");
    }

    private void handleSet(CommandSender sender, String[] args) {
        handleBalanceCommand(sender, args, PERM_SET, "SET", true,
                PlayerData::setPurchased, "set-success");
    }

    private void handleInfinite(CommandSender sender, String[] args) {
        if (!hasPermission(sender, PERM_INFINITE)) {
            flightManager.sendMessage(sender, "no-permission");
            return;
        }
        if (args.length < 3) {
            flightManager.sendRawMessage(sender, "<red>Usage: /aerofly infinite <all|player|group> [target] <duration></red>");
            return;
        }

        String sub = args[1].toLowerCase();
        switch (sub) {
            case "all" -> handleInfiniteAll(sender, args[2]);
            case "player" -> {
                if (args.length < 4) {
                    flightManager.sendRawMessage(sender, "<red>Usage: /aerofly infinite player <player> <duration></red>");
                    return;
                }
                handleInfinitePlayer(sender, args[2], args[3]);
            }
            case "group" -> {
                if (args.length < 4) {
                    flightManager.sendRawMessage(sender, "<red>Usage: /aerofly infinite group <group> <duration></red>");
                    return;
                }
                handleInfiniteGroup(sender, args[2], args[3]);
            }
            default -> flightManager.sendRawMessage(sender, "<red>Unknown infinite type. Use: all, player, or group.</red>");
        }
    }

    private void handleInfiniteAll(CommandSender sender, String durationStr) {
        long duration;
        try {
            duration = DurationParser.parse(durationStr, false);
        } catch (IllegalArgumentException e) {
            flightManager.sendRawMessage(sender, "<red>" + e.getMessage() + "</red>");
            return;
        }

        String creator = getSenderName(sender);
        flightManager.addGrant(Grant.GrantType.ALL, "ALL", duration, creator).thenAccept(grant -> {
            plugin.getLogger().info(String.format("[Admin Audit] %s created ALL grant #%d for %s", creator, grant.getId(), DurationParser.format(duration)));
            runOnMain(() -> flightManager.sendMessage(sender, "infinite-all-success",
                    Placeholder.unparsed("duration", DurationParser.format(duration)),
                    Placeholder.unparsed("id", String.valueOf(grant.getId()))));
        });
    }

    private void handleInfinitePlayer(CommandSender sender, String targetPlayer, String durationStr) {
        long duration;
        try {
            duration = DurationParser.parse(durationStr, false);
        } catch (IllegalArgumentException e) {
            flightManager.sendRawMessage(sender, "<red>" + e.getMessage() + "</red>");
            return;
        }

        String creator = getSenderName(sender);
        resolvePlayer(targetPlayer).thenAccept(resolved -> {
            if (resolved == null) {
                runOnMain(() -> flightManager.sendMessage(sender, "player-not-found", Placeholder.unparsed("player", targetPlayer)));
                return;
            }

            flightManager.addGrant(Grant.GrantType.PLAYER, resolved.uuid().toString(), duration, creator).thenAccept(grant -> {
                plugin.getLogger().info(String.format("[Admin Audit] %s created PLAYER grant #%d for %s (%s)", creator, grant.getId(), resolved.name(), resolved.uuid()));
                runOnMain(() -> flightManager.sendMessage(sender, "infinite-player-success",
                        Placeholder.unparsed("duration", DurationParser.format(duration)),
                        Placeholder.unparsed("player", resolved.name()),
                        Placeholder.unparsed("id", String.valueOf(grant.getId()))));
            });
        });
    }

    private void handleInfiniteGroup(CommandSender sender, String groupName, String durationStr) {
        long duration;
        try {
            duration = DurationParser.parse(durationStr, false);
        } catch (IllegalArgumentException e) {
            flightManager.sendRawMessage(sender, "<red>" + e.getMessage() + "</red>");
            return;
        }

        String creator = getSenderName(sender);
        flightManager.addGrant(Grant.GrantType.GROUP, groupName.toLowerCase(), duration, creator).thenAccept(grant -> {
            plugin.getLogger().info(String.format("[Admin Audit] %s created GROUP grant #%d for %s", creator, grant.getId(), groupName));
            runOnMain(() -> flightManager.sendMessage(sender, "infinite-group-success",
                    Placeholder.unparsed("duration", DurationParser.format(duration)),
                    Placeholder.unparsed("group", groupName),
                    Placeholder.unparsed("id", String.valueOf(grant.getId()))));
        });
    }

    private void handleGrants(CommandSender sender) {
        if (!hasPermission(sender, PERM_INFINITE)) {
            flightManager.sendMessage(sender, "no-permission");
            return;
        }

        List<Grant> grants = flightManager.getActiveGrants();
        if (grants.isEmpty()) {
            flightManager.sendMessage(sender, "grants-none");
            return;
        }

        flightManager.sendMessage(sender, "grants-header");
        for (Grant g : grants) {
            flightManager.sendMessage(sender, "grants-entry",
                    Placeholder.unparsed("id", String.valueOf(g.getId())),
                    Placeholder.unparsed("type", g.getType().name()),
                    Placeholder.unparsed("target", g.getTargetDisplay()),
                    Placeholder.unparsed("expiry", DurationParser.format(g.getRemainingSeconds())),
                    Placeholder.unparsed("creator", g.getCreatedBy()));
        }
    }

    private void handleRevoke(CommandSender sender, String[] args) {
        if (!hasPermission(sender, PERM_REVOKE)) {
            flightManager.sendMessage(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            flightManager.sendRawMessage(sender, "<red>Usage: /aerofly revoke <id></red>");
            return;
        }

        int grantId;
        try {
            grantId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            flightManager.sendRawMessage(sender, "<red>Grant ID must be a valid number.</red>");
            return;
        }

        flightManager.revokeGrant(grantId).thenAccept(success -> {
            if (success) {
                plugin.getLogger().info("[Admin Audit] " + getSenderName(sender) + " revoked grant #" + grantId);
                runOnMain(() -> flightManager.sendMessage(sender, "revoke-success", Placeholder.unparsed("id", String.valueOf(grantId))));
            } else {
                runOnMain(() -> flightManager.sendMessage(sender, "revoke-not-found", Placeholder.unparsed("id", String.valueOf(grantId))));
            }
        });
    }

    private void handleCheck(CommandSender sender, String[] args) {
        if (!hasPermission(sender, PERM_CHECK)) {
            flightManager.sendMessage(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            flightManager.sendRawMessage(sender, "<red>Usage: /aerofly check <player></red>");
            return;
        }

        String targetName = args[1];
        resolvePlayer(targetName).thenAccept(resolved -> {
            if (resolved == null) {
                runOnMain(() -> flightManager.sendMessage(sender, "player-not-found", Placeholder.unparsed("player", targetName)));
                return;
            }
            queryAndDisplayCheck(sender, resolved);
        });
    }

    private void queryAndDisplayCheck(CommandSender sender, ResolvedPlayer resolved) {
        if (resolved.isOnline()) {
            Player player = Bukkit.getPlayer(resolved.uuid());
            if (player != null) {
                PlayerData data = flightManager.getPlayerData(player.getUniqueId());
                Rank rank = flightManager.evaluatePlayerRank(player);
                boolean bypass = player.hasPermission("aerofly.bypass.time");
                List<Grant> grants = flightManager.getApplicableGrants(player.getUniqueId(), flightManager.getPlayerGroups(player));
                runOnMain(() -> displayCheck(sender, player.getName(), player.getUniqueId(), rank, data, bypass, grants));
                return;
            }
        }

        databaseManager.loadPlayerData(resolved.uuid(), resolved.name()).thenAccept(data ->
                flightManager.evaluateOfflineRank(resolved.uuid()).thenAccept(rank ->
                        flightManager.getOfflinePlayerGroups(resolved.uuid()).thenAccept(groups -> {
                            List<Grant> grants = flightManager.getApplicableGrants(resolved.uuid(), groups);
                            runOnMain(() -> displayCheck(sender, resolved.name(), resolved.uuid(), rank, data, false, grants));
                        })));
    }

    private void displayCheck(CommandSender sender, String name, UUID uuid, Rank rank, PlayerData data, boolean bypass, List<Grant> grants) {
        long allowance = rank != null ? rank.getDailyTimeSeconds() : 0L;
        long used = data != null ? data.getDailyUsedSeconds() : 0L;
        long dailyRemaining = data != null ? data.getDailyRemaining(allowance) : 0L;
        long purchased = data != null ? data.getPurchasedSeconds() : 0L;

        boolean isUnlimited = bypass || (!grants.isEmpty());

        flightManager.sendMessage(sender, "check-header");
        flightManager.sendMessage(sender, "check-player", Placeholder.unparsed("player", name), Placeholder.unparsed("uuid", uuid.toString()));
        flightManager.sendMessage(sender, "check-rank", Placeholder.unparsed("rank", rank != null ? rank.getName() : "None"));
        flightManager.sendMessage(sender, "check-daily-allowance", Placeholder.unparsed("allowance", DurationParser.format(allowance)));
        flightManager.sendMessage(sender, "check-daily-used", Placeholder.unparsed("used", DurationParser.format(used)));
        flightManager.sendMessage(sender, "check-daily-remaining", Placeholder.unparsed("remaining", DurationParser.format(dailyRemaining)));
        flightManager.sendMessage(sender, "check-purchased", Placeholder.unparsed("purchased", DurationParser.format(purchased)));
        flightManager.sendMessage(sender, "check-total-finite", Placeholder.unparsed("total", DurationParser.format(dailyRemaining + purchased)));
        flightManager.sendMessage(sender, "check-unlimited-active", Placeholder.unparsed("status", isUnlimited ? "<green>YES</green>" : "<red>NO</red>"));

        if (isUnlimited) {
            displayGrantDetails(sender, bypass, grants);
        }
        flightManager.sendMessage(sender, "check-footer");
    }

    private void displayGrantDetails(CommandSender sender, boolean bypass, List<Grant> grants) {
        flightManager.sendMessage(sender, "check-grants-header");
        if (bypass) {
            flightManager.sendRawMessage(sender, "<gray>  - <yellow>[PERMISSION]</yellow> aerofly.bypass.time | Permanent</gray>");
        }
        for (Grant g : grants) {
            flightManager.sendMessage(sender, "check-grant-item",
                    Placeholder.unparsed("type", g.getType().name()),
                    Placeholder.unparsed("source", g.getTargetDisplay()),
                    Placeholder.unparsed("expiry", DurationParser.format(g.getRemainingSeconds())));
        }
    }

    private void handleReload(CommandSender sender) {
        if (!hasPermission(sender, PERM_RELOAD)) {
            flightManager.sendMessage(sender, "no-permission");
            return;
        }
        plugin.reloadPluginConfig();
        flightManager.sendMessage(sender, "reload-success");
    }

    private void logAdminAction(CommandSender sender, String action, String target, UUID uuid, String amount, String balance) {
        plugin.getLogger().info(String.format("[Admin Audit] Sender: %s | Target: %s (%s) | Action: %s | Amount: %s | Resulting Balance: %s",
                getSenderName(sender), target, uuid, action, amount, balance));
    }

    private String getSenderName(CommandSender sender) {
        return (sender instanceof Player p) ? p.getName() : "CONSOLE";
    }

    private void runOnMain(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) runnable.run();
        else Bukkit.getScheduler().runTask(plugin, runnable);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) return completeSubcommands(sender, args[0]);
        if (args.length == 2) return completeArgTwo(args[0], args[1]);
        if (args.length == 3) return completeArgThree(args[0], args[1], args[2]);
        if (args.length == 4 && args[0].equalsIgnoreCase("infinite")) return filterMatches(DURATION_SUGGESTIONS, args[3]);
        return Collections.emptyList();
    }

    private List<String> completeSubcommands(CommandSender sender, String prefix) {
        List<String> subs = new ArrayList<>();
        if (hasPermission(sender, PERM_ADD)) subs.add("add");
        if (hasPermission(sender, PERM_REMOVE)) subs.add("remove");
        if (hasPermission(sender, PERM_SET)) subs.add("set");
        if (hasPermission(sender, PERM_INFINITE)) {
            subs.add("infinite");
            subs.add("grants");
        }
        if (hasPermission(sender, PERM_REVOKE)) subs.add("revoke");
        if (hasPermission(sender, PERM_CHECK)) subs.add("check");
        if (hasPermission(sender, PERM_RELOAD)) subs.add("reload");
        subs.add("help");
        return filterMatches(subs, prefix);
    }

    private List<String> completeArgTwo(String sub, String prefix) {
        return switch (sub.toLowerCase()) {
            case "add", "remove", "set", "check" -> filterMatches(getOnlinePlayerNames(), prefix);
            case "infinite" -> filterMatches(List.of("all", "player", "group"), prefix);
            case "revoke" -> {
                List<String> grantIds = new ArrayList<>();
                for (Grant g : flightManager.getActiveGrants()) grantIds.add(String.valueOf(g.getId()));
                yield filterMatches(grantIds, prefix);
            }
            default -> Collections.emptyList();
        };
    }

    private List<String> completeArgThree(String sub, String subTwo, String prefix) {
        return switch (sub.toLowerCase()) {
            case "add", "remove", "set" -> filterMatches(DURATION_SUGGESTIONS, prefix);
            case "infinite" -> switch (subTwo.toLowerCase()) {
                case "all" -> filterMatches(DURATION_SUGGESTIONS, prefix);
                case "player" -> filterMatches(getOnlinePlayerNames(), prefix);
                case "group" -> filterMatches(getLoadedLuckPermsGroups(), prefix);
                default -> Collections.emptyList();
            };
            default -> Collections.emptyList();
        };
    }

    private List<String> getOnlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
        return names;
    }

    private List<String> getLoadedLuckPermsGroups() {
        List<String> groups = new ArrayList<>();
        LuckPerms lp = flightManager.getLuckPerms();
        if (lp != null) lp.getGroupManager().getLoadedGroups().forEach(g -> groups.add(g.getName()));
        return groups;
    }

    private List<String> filterMatches(List<String> source, String prefix) {
        if (prefix == null || prefix.isEmpty()) return source;
        List<String> matches = new ArrayList<>();
        String lower = prefix.toLowerCase();
        for (String s : source) {
            if (s.toLowerCase().startsWith(lower)) matches.add(s);
        }
        return matches;
    }
}
