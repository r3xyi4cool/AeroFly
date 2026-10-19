package org.rexyiscool.aeroFly;

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class AeroFlyCommand implements CommandExecutor, TabCompleter {

    private final AeroFly plugin;
    private final FlightManager flightManager;
    private final DatabaseManager databaseManager;

    private static final List<String> DURATION_SUGGESTIONS = List.of("30s", "15m", "1h", "2h", "1d", "7d");

    public AeroFlyCommand(AeroFly plugin, FlightManager flightManager, DatabaseManager databaseManager) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin cannot be null");
        this.flightManager = Objects.requireNonNull(flightManager, "FlightManager cannot be null");
        this.databaseManager = Objects.requireNonNull(databaseManager, "DatabaseManager cannot be null");
    }

    private static class ResolvedPlayer {
        final UUID uuid;
        final String name;
        final boolean isOnline;

        ResolvedPlayer(UUID uuid, String name, boolean isOnline) {
            this.uuid = uuid;
            this.name = name;
            this.isOnline = isOnline;
        }
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
        // Try cached offline player
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && cached.hasPlayedBefore()) {
            future.complete(new ResolvedPlayer(cached.getUniqueId(), cached.getName() != null ? cached.getName() : name, false));
            return;
        }

        // Check offline players without making blocking web requests
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() != null && op.getName().equalsIgnoreCase(name) && op.hasPlayedBefore()) {
                future.complete(new ResolvedPlayer(op.getUniqueId(), op.getName(), false));
                return;
            }
        }

        // Not found
        future.complete(null);
    }

    private boolean hasPermission(CommandSender sender, String perm) {
        if (!(sender instanceof Player)) {
            return true; // Console always allowed
        }
        return sender.hasPermission(perm) || sender.hasPermission("aerofly.admin");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "add":
                handleAdd(sender, args);
                break;
            case "remove":
                handleRemove(sender, args);
                break;
            case "set":
                handleSet(sender, args);
                break;
            case "infinite":
                handleInfinite(sender, args);
                break;
            case "grants":
                handleGrants(sender);
                break;
            case "revoke":
                handleRevoke(sender, args);
                break;
            case "check":
                handleCheck(sender, args);
                break;
            case "reload":
                handleReload(sender);
                break;
            default:
                sendHelp(sender, label);
                break;
        }
        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        flightManager.sendRawMessage(sender, "<gradient:#00c6ff:#0072ff><bold>======= AeroFly Commands =======</bold></gradient>");

        if (sender.hasPermission("aerofly.use")) {
            flightManager.sendRawMessage(sender, "<gray>/fly <dark_gray>- <white>Toggle your flight mode</white></gray>");
        }

        if (hasPermission(sender, "aerofly.admin.add")) {
            flightManager.sendRawMessage(sender, "<gray>/" + label + " add <player> <time> <dark_gray>- <white>Add purchased flight time</white></gray>");
        }
        if (hasPermission(sender, "aerofly.admin.remove")) {
            flightManager.sendRawMessage(sender, "<gray>/" + label + " remove <player> <time> <dark_gray>- <white>Deduct purchased flight time</white></gray>");
        }
        if (hasPermission(sender, "aerofly.admin.set")) {
            flightManager.sendRawMessage(sender, "<gray>/" + label + " set <player> <time> <dark_gray>- <white>Set purchased balance (use 0s for zero)</white></gray>");
        }
        if (hasPermission(sender, "aerofly.admin.infinite")) {
            flightManager.sendRawMessage(sender, "<gray>/" + label + " infinite all <time> <dark_gray>- <white>Grant temporary unlimited flight globally</white></gray>");
            flightManager.sendRawMessage(sender, "<gray>/" + label + " infinite player <player> <time> <dark_gray>- <white>Grant temporary unlimited flight to player</white></gray>");
            flightManager.sendRawMessage(sender, "<gray>/" + label + " infinite group <group> <time> <dark_gray>- <white>Grant temporary unlimited flight to group</white></gray>");
            flightManager.sendRawMessage(sender, "<gray>/" + label + " grants <dark_gray>- <white>List all active unlimited grants</white></gray>");
        }
        if (hasPermission(sender, "aerofly.admin.revoke")) {
            flightManager.sendRawMessage(sender, "<gray>/" + label + " revoke <id> <dark_gray>- <white>Revoke an active grant by ID</white></gray>");
        }
        if (hasPermission(sender, "aerofly.admin.check")) {
            flightManager.sendRawMessage(sender, "<gray>/" + label + " check <player> <dark_gray>- <white>Inspect player balance and grants</white></gray>");
        }
        if (hasPermission(sender, "aerofly.admin.reload")) {
            flightManager.sendRawMessage(sender, "<gray>/" + label + " reload <dark_gray>- <white>Reload configuration and ranks</white></gray>");
        }

        flightManager.sendRawMessage(sender, "<gradient:#00c6ff:#0072ff><bold>=================================</bold></gradient>");
    }

    // =========================================================================
    //                            Subcommand Handlers
    // =========================================================================

    private void handleAdd(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "aerofly.admin.add")) {
            flightManager.sendMessage(sender, "no-permission");
            return;
        }

        if (args.length < 3) {
            flightManager.sendRawMessage(sender, "<red>Usage: /aerofly add <player> <duration></red>");
            return;
        }

        long seconds;
        try {
            seconds = DurationParser.parse(args[2], false);
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

            if (resolved.isOnline) {
                PlayerData data = flightManager.getPlayerData(resolved.uuid);
                if (data != null) {
                    long newBalance = data.addPurchased(seconds, DurationParser.MAX_SECONDS);
                    databaseManager.savePlayerData(data);
                    logAdminAction(sender, "ADD", resolved.name, resolved.uuid, DurationParser.format(seconds), DurationParser.format(newBalance));
                    runOnMain(() -> flightManager.sendMessage(sender, "add-success",
                            Placeholder.unparsed("amount", DurationParser.format(seconds)),
                            Placeholder.unparsed("player", resolved.name),
                            Placeholder.unparsed("balance", DurationParser.format(newBalance))));
                    return;
                }
            }

            // Offline player
            databaseManager.modifyPlayerDataAsync(resolved.uuid, resolved.name, data -> {
                data.addPurchased(seconds, DurationParser.MAX_SECONDS);
            }).thenAccept(updated -> {
                logAdminAction(sender, "ADD", resolved.name, resolved.uuid, DurationParser.format(seconds), DurationParser.format(updated.getPurchasedSeconds()));
                runOnMain(() -> flightManager.sendMessage(sender, "add-success",
                        Placeholder.unparsed("amount", DurationParser.format(seconds)),
                        Placeholder.unparsed("player", resolved.name),
                        Placeholder.unparsed("balance", DurationParser.format(updated.getPurchasedSeconds()))));
            });
        });
    }

    private void handleRemove(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "aerofly.admin.remove")) {
            flightManager.sendMessage(sender, "no-permission");
            return;
        }

        if (args.length < 3) {
            flightManager.sendRawMessage(sender, "<red>Usage: /aerofly remove <player> <duration></red>");
            return;
        }

        long seconds;
        try {
            seconds = DurationParser.parse(args[2], false);
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

            if (resolved.isOnline) {
                PlayerData data = flightManager.getPlayerData(resolved.uuid);
                if (data != null) {
                    long newBalance = data.removePurchased(seconds);
                    databaseManager.savePlayerData(data);
                    logAdminAction(sender, "REMOVE", resolved.name, resolved.uuid, DurationParser.format(seconds), DurationParser.format(newBalance));
                    runOnMain(() -> flightManager.sendMessage(sender, "remove-success",
                            Placeholder.unparsed("amount", DurationParser.format(seconds)),
                            Placeholder.unparsed("player", resolved.name),
                            Placeholder.unparsed("balance", DurationParser.format(newBalance))));
                    return;
                }
            }

            // Offline player
            databaseManager.modifyPlayerDataAsync(resolved.uuid, resolved.name, data -> {
                data.removePurchased(seconds);
            }).thenAccept(updated -> {
                logAdminAction(sender, "REMOVE", resolved.name, resolved.uuid, DurationParser.format(seconds), DurationParser.format(updated.getPurchasedSeconds()));
                runOnMain(() -> flightManager.sendMessage(sender, "remove-success",
                        Placeholder.unparsed("amount", DurationParser.format(seconds)),
                        Placeholder.unparsed("player", resolved.name),
                        Placeholder.unparsed("balance", DurationParser.format(updated.getPurchasedSeconds()))));
            });
        });
    }

    private void handleSet(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "aerofly.admin.set")) {
            flightManager.sendMessage(sender, "no-permission");
            return;
        }

        if (args.length < 3) {
            flightManager.sendRawMessage(sender, "<red>Usage: /aerofly set <player> <duration> (use 0s to set to zero)</red>");
            return;
        }

        long seconds;
        try {
            seconds = DurationParser.parse(args[2], true);
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

            if (resolved.isOnline) {
                PlayerData data = flightManager.getPlayerData(resolved.uuid);
                if (data != null) {
                    long newBalance = data.setPurchased(seconds);
                    databaseManager.savePlayerData(data);
                    logAdminAction(sender, "SET", resolved.name, resolved.uuid, DurationParser.format(seconds), DurationParser.format(newBalance));
                    runOnMain(() -> flightManager.sendMessage(sender, "set-success",
                            Placeholder.unparsed("player", resolved.name),
                            Placeholder.unparsed("balance", DurationParser.format(newBalance))));
                    return;
                }
            }

            // Offline player
            databaseManager.modifyPlayerDataAsync(resolved.uuid, resolved.name, data -> {
                data.setPurchased(seconds);
            }).thenAccept(updated -> {
                logAdminAction(sender, "SET", resolved.name, resolved.uuid, DurationParser.format(seconds), DurationParser.format(updated.getPurchasedSeconds()));
                runOnMain(() -> flightManager.sendMessage(sender, "set-success",
                        Placeholder.unparsed("player", resolved.name),
                        Placeholder.unparsed("balance", DurationParser.format(updated.getPurchasedSeconds()))));
            });
        });
    }

    private void handleInfinite(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "aerofly.admin.infinite")) {
            flightManager.sendMessage(sender, "no-permission");
            return;
        }

        if (args.length < 3) {
            flightManager.sendRawMessage(sender, "<red>Usage: /aerofly infinite <all|player|group> [target] <duration></red>");
            return;
        }

        String type = args[1].toLowerCase();
        String creatorName = (sender instanceof Player p) ? p.getName() : "CONSOLE";

        switch (type) {
            case "all": {
                long duration;
                try {
                    duration = DurationParser.parse(args[2], false);
                } catch (IllegalArgumentException e) {
                    flightManager.sendRawMessage(sender, "<red>" + e.getMessage() + "</red>");
                    return;
                }

                flightManager.addGrant(Grant.GrantType.ALL, "ALL", duration, creatorName).thenAccept(grant -> {
                    plugin.getLogger().info(String.format("[Admin Audit] %s created ALL infinite grant #%d for %s",
                            creatorName, grant.getId(), DurationParser.format(duration)));
                    runOnMain(() -> flightManager.sendMessage(sender, "infinite-all-success",
                            Placeholder.unparsed("duration", DurationParser.format(duration)),
                            Placeholder.unparsed("id", String.valueOf(grant.getId()))));
                });
                break;
            }
            case "player": {
                if (args.length < 4) {
                    flightManager.sendRawMessage(sender, "<red>Usage: /aerofly infinite player <player> <duration></red>");
                    return;
                }

                String targetPlayer = args[2];
                long duration;
                try {
                    duration = DurationParser.parse(args[3], false);
                } catch (IllegalArgumentException e) {
                    flightManager.sendRawMessage(sender, "<red>" + e.getMessage() + "</red>");
                    return;
                }

                resolvePlayer(targetPlayer).thenAccept(resolved -> {
                    if (resolved == null) {
                        runOnMain(() -> flightManager.sendMessage(sender, "player-not-found", Placeholder.unparsed("player", targetPlayer)));
                        return;
                    }

                    flightManager.addGrant(Grant.GrantType.PLAYER, resolved.uuid.toString(), duration, creatorName).thenAccept(grant -> {
                        plugin.getLogger().info(String.format("[Admin Audit] %s created PLAYER infinite grant #%d for %s (%s) for %s",
                                creatorName, grant.getId(), resolved.name, resolved.uuid, DurationParser.format(duration)));
                        runOnMain(() -> flightManager.sendMessage(sender, "infinite-player-success",
                                Placeholder.unparsed("duration", DurationParser.format(duration)),
                                Placeholder.unparsed("player", resolved.name),
                                Placeholder.unparsed("id", String.valueOf(grant.getId()))));
                    });
                });
                break;
            }
            case "group": {
                if (args.length < 4) {
                    flightManager.sendRawMessage(sender, "<red>Usage: /aerofly infinite group <group> <duration></red>");
                    return;
                }

                String groupName = args[2].toLowerCase();
                long duration;
                try {
                    duration = DurationParser.parse(args[3], false);
                } catch (IllegalArgumentException e) {
                    flightManager.sendRawMessage(sender, "<red>" + e.getMessage() + "</red>");
                    return;
                }

                flightManager.addGrant(Grant.GrantType.GROUP, groupName, duration, creatorName).thenAccept(grant -> {
                    plugin.getLogger().info(String.format("[Admin Audit] %s created GROUP infinite grant #%d for %s for %s",
                            creatorName, grant.getId(), groupName, DurationParser.format(duration)));
                    runOnMain(() -> flightManager.sendMessage(sender, "infinite-group-success",
                            Placeholder.unparsed("duration", DurationParser.format(duration)),
                            Placeholder.unparsed("group", groupName),
                            Placeholder.unparsed("id", String.valueOf(grant.getId()))));
                });
                break;
            }
            default:
                flightManager.sendRawMessage(sender, "<red>Unknown infinite type. Use: all, player, or group.</red>");
                break;
        }
    }

    private void handleGrants(CommandSender sender) {
        if (!hasPermission(sender, "aerofly.admin.infinite")) {
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
        if (!hasPermission(sender, "aerofly.admin.revoke")) {
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
                String admin = (sender instanceof Player p) ? p.getName() : "CONSOLE";
                plugin.getLogger().info("[Admin Audit] " + admin + " revoked grant #" + grantId);
                runOnMain(() -> flightManager.sendMessage(sender, "revoke-success",
                        Placeholder.unparsed("id", String.valueOf(grantId))));
            } else {
                runOnMain(() -> flightManager.sendMessage(sender, "revoke-not-found",
                        Placeholder.unparsed("id", String.valueOf(grantId))));
            }
        });
    }

    private void handleCheck(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "aerofly.admin.check")) {
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

            if (resolved.isOnline) {
                Player player = Bukkit.getPlayer(resolved.uuid);
                if (player != null) {
                    PlayerData data = flightManager.getPlayerData(player.getUniqueId());
                    Rank rank = flightManager.evaluatePlayerRank(player);
                    boolean hasBypass = player.hasPermission("aerofly.bypass.time");
                    Set<String> groups = flightManager.getPlayerGroups(player);
                    List<Grant> grants = flightManager.getApplicableGrants(player.getUniqueId(), groups);

                    runOnMain(() -> displayCheck(sender, player.getName(), player.getUniqueId(), rank, data, hasBypass, grants));
                    return;
                }
            }

            // Offline check
            databaseManager.loadPlayerData(resolved.uuid, resolved.name).thenAccept(offlineData -> {
                flightManager.evaluateOfflineRank(resolved.uuid).thenAccept(rank -> {
                    flightManager.getOfflinePlayerGroups(resolved.uuid).thenAccept(groups -> {
                        List<Grant> grants = flightManager.getApplicableGrants(resolved.uuid, groups);
                        runOnMain(() -> displayCheck(sender, resolved.name, resolved.uuid, rank, offlineData, false, grants));
                    });
                });
            });
        });
    }

    private void displayCheck(CommandSender sender, String name, UUID uuid, Rank rank, PlayerData data, boolean hasBypass, List<Grant> grants) {
        long allowance = rank != null ? rank.getDailyTimeSeconds() : 0L;
        long used = data != null ? data.getDailyUsedSeconds() : 0L;
        long dailyRemaining = data != null ? data.getDailyRemaining(allowance) : 0L;
        long purchased = data != null ? data.getPurchasedSeconds() : 0L;
        long totalFinite = dailyRemaining + purchased;

        boolean isUnlimited = hasBypass || (!grants.isEmpty());

        flightManager.sendMessage(sender, "check-header");
        flightManager.sendMessage(sender, "check-player",
                Placeholder.unparsed("player", name),
                Placeholder.unparsed("uuid", uuid.toString()));
        flightManager.sendMessage(sender, "check-rank",
                Placeholder.unparsed("rank", rank != null ? rank.getName() : "None"));
        flightManager.sendMessage(sender, "check-daily-allowance",
                Placeholder.unparsed("allowance", DurationParser.format(allowance)));
        flightManager.sendMessage(sender, "check-daily-used",
                Placeholder.unparsed("used", DurationParser.format(used)));
        flightManager.sendMessage(sender, "check-daily-remaining",
                Placeholder.unparsed("remaining", DurationParser.format(dailyRemaining)));
        flightManager.sendMessage(sender, "check-purchased",
                Placeholder.unparsed("purchased", DurationParser.format(purchased)));
        flightManager.sendMessage(sender, "check-total-finite",
                Placeholder.unparsed("total", DurationParser.format(totalFinite)));
        flightManager.sendMessage(sender, "check-unlimited-active",
                Placeholder.unparsed("status", isUnlimited ? "<green>YES</green>" : "<red>NO</red>"));

        if (isUnlimited) {
            flightManager.sendMessage(sender, "check-grants-header");
            if (hasBypass) {
                flightManager.sendRawMessage(sender, "<gray>  - <yellow>[PERMISSION]</yellow> aerofly.bypass.time | Permanent</gray>");
            }
            for (Grant g : grants) {
                flightManager.sendMessage(sender, "check-grant-item",
                        Placeholder.unparsed("type", g.getType().name()),
                        Placeholder.unparsed("source", g.getTargetDisplay()),
                        Placeholder.unparsed("expiry", DurationParser.format(g.getRemainingSeconds())));
            }
        }

        flightManager.sendMessage(sender, "check-footer");
    }

    private void handleReload(CommandSender sender) {
        if (!hasPermission(sender, "aerofly.admin.reload")) {
            flightManager.sendMessage(sender, "no-permission");
            return;
        }

        plugin.reloadPluginConfig();
        flightManager.sendMessage(sender, "reload-success");
    }

    private void logAdminAction(CommandSender sender, String action, String targetName, UUID targetUuid, String amount, String balance) {
        String senderName = (sender instanceof Player p) ? p.getName() : "CONSOLE";
        plugin.getLogger().info(String.format("[Admin Audit] Sender: %s | Target: %s (%s) | Action: %s | Amount: %s | Resulting Balance: %s",
                senderName, targetName, targetUuid, action, amount, balance));
    }

    private void runOnMain(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    // =========================================================================
    //                            Tab Completion
    // =========================================================================

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            if (hasPermission(sender, "aerofly.admin.add")) subs.add("add");
            if (hasPermission(sender, "aerofly.admin.remove")) subs.add("remove");
            if (hasPermission(sender, "aerofly.admin.set")) subs.add("set");
            if (hasPermission(sender, "aerofly.admin.infinite")) {
                subs.add("infinite");
                subs.add("grants");
            }
            if (hasPermission(sender, "aerofly.admin.revoke")) subs.add("revoke");
            if (hasPermission(sender, "aerofly.admin.check")) subs.add("check");
            if (hasPermission(sender, "aerofly.admin.reload")) subs.add("reload");
            subs.add("help");

            return filterMatches(subs, args[0]);
        }

        String sub = args[0].toLowerCase();

        if (args.length == 2) {
            switch (sub) {
                case "add":
                case "remove":
                case "set":
                case "check":
                    return filterMatches(getOnlinePlayerNames(), args[1]);
                case "infinite":
                    return filterMatches(List.of("all", "player", "group"), args[1]);
                case "revoke":
                    List<String> grantIds = new ArrayList<>();
                    for (Grant g : flightManager.getActiveGrants()) {
                        grantIds.add(String.valueOf(g.getId()));
                    }
                    return filterMatches(grantIds, args[1]);
            }
        }

        if (args.length == 3) {
            switch (sub) {
                case "add":
                case "remove":
                case "set":
                    return filterMatches(DURATION_SUGGESTIONS, args[2]);
                case "infinite":
                    String type = args[1].toLowerCase();
                    if (type.equals("all")) {
                        return filterMatches(DURATION_SUGGESTIONS, args[2]);
                    } else if (type.equals("player")) {
                        return filterMatches(getOnlinePlayerNames(), args[2]);
                    } else if (type.equals("group")) {
                        return filterMatches(getLoadedLuckPermsGroups(), args[2]);
                    }
                    break;
            }
        }

        if (args.length == 4 && sub.equals("infinite")) {
            String type = args[1].toLowerCase();
            if (type.equals("player") || type.equals("group")) {
                return filterMatches(DURATION_SUGGESTIONS, args[3]);
            }
        }

        return Collections.emptyList();
    }

    private List<String> getOnlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        return names;
    }

    private List<String> getLoadedLuckPermsGroups() {
        List<String> groups = new ArrayList<>();
        LuckPerms lp = flightManager.getLuckPerms();
        if (lp != null) {
            lp.getGroupManager().getLoadedGroups().forEach(g -> groups.add(g.getName()));
        }
        return groups;
    }

    private List<String> filterMatches(List<String> source, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return source;
        }
        List<String> matches = new ArrayList<>();
        String lower = prefix.toLowerCase();
        for (String s : source) {
            if (s.toLowerCase().startsWith(lower)) {
                matches.add(s);
            }
        }
        return matches;
    }
}
