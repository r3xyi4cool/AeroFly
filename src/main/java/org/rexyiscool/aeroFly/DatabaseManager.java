package org.rexyiscool.aeroFly;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class DatabaseManager {

    private final AeroFly plugin;
    private final File databaseFile;
    private final ExecutorService dbExecutor;
    private Connection connection;

    public DatabaseManager(AeroFly plugin, String databaseName) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin cannot be null");
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        this.databaseFile = new File(dataFolder, databaseName != null ? databaseName : "aerofly.db");
        this.dbExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "AeroFly-DB-Thread");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void initialize() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found on classpath", e);
        }

        String url = "jdbc:sqlite:" + databaseFile.getAbsolutePath();
        this.connection = DriverManager.getConnection(url);

        try (Statement stmt = connection.createStatement()) {
            // Enable Write-Ahead Logging and busy timeout for high concurrency and performance
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA synchronous=NORMAL;");
            stmt.execute("PRAGMA busy_timeout=5000;");

            // Players table
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS players (" +
                            "uuid VARCHAR(36) PRIMARY KEY, " +
                            "last_name VARCHAR(16), " +
                            "daily_used_seconds BIGINT NOT NULL DEFAULT 0, " +
                            "last_reset_period BIGINT NOT NULL DEFAULT 0, " +
                            "purchased_seconds BIGINT NOT NULL DEFAULT 0, " +
                            "updated_at BIGINT NOT NULL DEFAULT 0" +
                            ");"
            );

            // Grants table
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS grants (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "type VARCHAR(16) NOT NULL, " +
                            "target VARCHAR(64), " +
                            "expires_at BIGINT NOT NULL, " +
                            "created_by VARCHAR(36) NOT NULL, " +
                            "created_at BIGINT NOT NULL" +
                            ");"
            );
        }
    }

    private synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            String url = "jdbc:sqlite:" + databaseFile.getAbsolutePath();
            connection = DriverManager.getConnection(url);
        }
        return connection;
    }

    /**
     * Loads a player's data from the database asynchronously.
     */
    public CompletableFuture<PlayerData> loadPlayerData(UUID uuid, String currentName) {
        CompletableFuture<PlayerData> future = new CompletableFuture<>();
        dbExecutor.submit(() -> {
            try {
                Connection conn = getConnection();
                String sql = "SELECT last_name, daily_used_seconds, last_reset_period, purchased_seconds, updated_at " +
                        "FROM players WHERE uuid = ?;";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            String lastName = rs.getString("last_name");
                            if (currentName != null && !currentName.isEmpty()) {
                                lastName = currentName;
                            }
                            long dailyUsed = rs.getLong("daily_used_seconds");
                            long lastReset = rs.getLong("last_reset_period");
                            long purchased = rs.getLong("purchased_seconds");
                            long updatedAt = rs.getLong("updated_at");

                            PlayerData data = new PlayerData(uuid, lastName, dailyUsed, lastReset, purchased, updatedAt);
                            future.complete(data);
                            return;
                        }
                    }
                }

                // If not found in DB, create initial record
                PlayerData newData = new PlayerData(uuid, currentName, 0L, 0L, 0L, System.currentTimeMillis());
                savePlayerDataSync(newData);
                future.complete(newData);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load player data for UUID " + uuid, e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Saves a single player's data synchronously on the DB thread.
     */
    private void savePlayerDataSync(PlayerData data) throws SQLException {
        Connection conn = getConnection();
        String sql = "INSERT INTO players (uuid, last_name, daily_used_seconds, last_reset_period, purchased_seconds, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(uuid) DO UPDATE SET " +
                "last_name = excluded.last_name, " +
                "daily_used_seconds = excluded.daily_used_seconds, " +
                "last_reset_period = excluded.last_reset_period, " +
                "purchased_seconds = excluded.purchased_seconds, " +
                "updated_at = excluded.updated_at;";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, data.getUuid().toString());
            ps.setString(2, data.getLastName());
            ps.setLong(3, data.getDailyUsedSeconds());
            ps.setLong(4, data.getLastResetPeriod());
            ps.setLong(5, data.getPurchasedSeconds());
            ps.setLong(6, data.getUpdatedAt());
            ps.executeUpdate();
            data.setDirty(false);
        }
    }

    /**
     * Saves player data asynchronously.
     */
    public CompletableFuture<Void> savePlayerData(PlayerData data) {
        PlayerData snapshot = data.snapshot();
        CompletableFuture<Void> future = new CompletableFuture<>();
        dbExecutor.submit(() -> {
            try {
                savePlayerDataSync(snapshot);
                future.complete(null);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save player data for UUID " + snapshot.getUuid(), e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Saves all dirty player data in a single transaction.
     */
    public void saveAllPlayers(Collection<PlayerData> players, boolean synchronous) {
        List<PlayerData> snapshots = new ArrayList<>();
        for (PlayerData p : players) {
            if (p.isDirty()) {
                snapshots.add(p.snapshot());
            }
        }

        if (snapshots.isEmpty()) {
            return;
        }

        Runnable task = () -> {
            try {
                Connection conn = getConnection();
                boolean prevAutoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    String sql = "INSERT INTO players (uuid, last_name, daily_used_seconds, last_reset_period, purchased_seconds, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?) " +
                            "ON CONFLICT(uuid) DO UPDATE SET " +
                            "last_name = excluded.last_name, " +
                            "daily_used_seconds = excluded.daily_used_seconds, " +
                            "last_reset_period = excluded.last_reset_period, " +
                            "purchased_seconds = excluded.purchased_seconds, " +
                            "updated_at = excluded.updated_at;";

                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        for (PlayerData data : snapshots) {
                            ps.setString(1, data.getUuid().toString());
                            ps.setString(2, data.getLastName());
                            ps.setLong(3, data.getDailyUsedSeconds());
                            ps.setLong(4, data.getLastResetPeriod());
                            ps.setLong(5, data.getPurchasedSeconds());
                            ps.setLong(6, data.getUpdatedAt());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                    conn.commit();
                } catch (Exception ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(prevAutoCommit);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to batch save player data", e);
            }
        };

        if (synchronous) {
            task.run();
        } else {
            dbExecutor.submit(task);
        }
    }

    /**
     * Safely modifies an offline (or online) player in the database and returns the updated state.
     */
    public CompletableFuture<PlayerData> modifyPlayerDataAsync(UUID uuid, String fallbackName, Consumer<PlayerData> modifier) {
        CompletableFuture<PlayerData> future = new CompletableFuture<>();
        dbExecutor.submit(() -> {
            try {
                Connection conn = getConnection();
                PlayerData data = null;

                String sql = "SELECT last_name, daily_used_seconds, last_reset_period, purchased_seconds, updated_at " +
                        "FROM players WHERE uuid = ?;";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            data = new PlayerData(
                                    uuid,
                                    rs.getString("last_name"),
                                    rs.getLong("daily_used_seconds"),
                                    rs.getLong("last_reset_period"),
                                    rs.getLong("purchased_seconds"),
                                    rs.getLong("updated_at")
                            );
                        }
                    }
                }

                if (data == null) {
                    data = new PlayerData(uuid, fallbackName, 0L, 0L, 0L, System.currentTimeMillis());
                }

                // Apply modification
                modifier.accept(data);

                // Save back to database
                savePlayerDataSync(data);
                future.complete(data);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to modify player data for UUID " + uuid, e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Loads all active grants from the database.
     */
    public CompletableFuture<List<Grant>> loadAllGrants() {
        CompletableFuture<List<Grant>> future = new CompletableFuture<>();
        dbExecutor.submit(() -> {
            List<Grant> list = new ArrayList<>();
            try {
                Connection conn = getConnection();
                String sql = "SELECT id, type, target, expires_at, created_by, created_at FROM grants;";
                try (PreparedStatement ps = conn.prepareStatement(sql);
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int id = rs.getInt("id");
                        Grant.GrantType type = Grant.GrantType.fromString(rs.getString("type"));
                        String target = rs.getString("target");
                        long expiresAt = rs.getLong("expires_at");
                        String createdBy = rs.getString("created_by");
                        long createdAt = rs.getLong("created_at");

                        list.add(new Grant(id, type, target, expiresAt, createdBy, createdAt));
                    }
                }
                future.complete(list);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load grants from database", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Inserts a new grant into the database and returns the Grant with generated ID.
     */
    public CompletableFuture<Grant> insertGrant(Grant.GrantType type, String target, long expiresAt, String createdBy) {
        CompletableFuture<Grant> future = new CompletableFuture<>();
        long createdAt = System.currentTimeMillis();
        dbExecutor.submit(() -> {
            try {
                Connection conn = getConnection();
                String sql = "INSERT INTO grants (type, target, expires_at, created_by, created_at) VALUES (?, ?, ?, ?, ?);";
                try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, type.name());
                    ps.setString(2, target);
                    ps.setLong(3, expiresAt);
                    ps.setString(4, createdBy);
                    ps.setLong(5, createdAt);
                    ps.executeUpdate();

                    try (ResultSet generatedKeys = ps.getGeneratedKeys()) {
                        if (generatedKeys.next()) {
                            int generatedId = generatedKeys.getInt(1);
                            future.complete(new Grant(generatedId, type, target, expiresAt, createdBy, createdAt));
                            return;
                        }
                    }
                }
                future.completeExceptionally(new SQLException("Creating grant failed, no ID obtained."));
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to insert grant into database", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Revokes (deletes) a grant by ID.
     */
    public CompletableFuture<Boolean> revokeGrant(int grantId) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        dbExecutor.submit(() -> {
            try {
                Connection conn = getConnection();
                String sql = "DELETE FROM grants WHERE id = ?;";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setInt(1, grantId);
                    int affected = ps.executeUpdate();
                    future.complete(affected > 0);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to revoke grant #" + grantId, e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Purges expired grants from the database.
     */
    public CompletableFuture<Integer> purgeExpiredGrants() {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        dbExecutor.submit(() -> {
            try {
                Connection conn = getConnection();
                String sql = "DELETE FROM grants WHERE expires_at <= ?;";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, System.currentTimeMillis());
                    int count = ps.executeUpdate();
                    future.complete(count);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to purge expired grants", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Synchronously flushes dirty data and closes database resources.
     */
    public synchronized void close(Collection<PlayerData> onlinePlayers) {
        try {
            // Save all online players synchronously before closing
            saveAllPlayers(onlinePlayers, true);

            dbExecutor.shutdown();
            if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                dbExecutor.shutdownNow();
            }

            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error while closing SQLite database", e);
        }
    }
}
