package org.rexyiscool.aeroFly.database;

import org.rexyiscool.aeroFly.AeroFly;
import org.rexyiscool.aeroFly.model.Grant;
import org.rexyiscool.aeroFly.model.PlayerData;

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

    private static final String UPSERT_PLAYER_SQL =
            "INSERT INTO players (uuid, last_name, daily_used_seconds, last_reset_period, purchased_seconds, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT(uuid) DO UPDATE SET " +
            "last_name = excluded.last_name, " +
            "daily_used_seconds = excluded.daily_used_seconds, " +
            "last_reset_period = excluded.last_reset_period, " +
            "purchased_seconds = excluded.purchased_seconds, " +
            "updated_at = excluded.updated_at;";

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

        Connection conn = getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA synchronous=NORMAL;");
            stmt.execute("PRAGMA busy_timeout=5000;");
            createTables(stmt);
        }
    }

    private void createTables(Statement stmt) throws SQLException {
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

    private synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            String url = "jdbc:sqlite:" + databaseFile.getAbsolutePath();
            connection = DriverManager.getConnection(url);
        }
        return connection;
    }

    public CompletableFuture<PlayerData> loadPlayerData(UUID uuid, String currentName) {
        CompletableFuture<PlayerData> future = new CompletableFuture<>();
        dbExecutor.submit(() -> {
            try {
                Connection conn = getConnection();
                PlayerData data = queryPlayer(conn, uuid, currentName);
                if (data == null) {
                    data = new PlayerData(uuid, currentName, 0L, 0L, 0L, System.currentTimeMillis());
                    savePlayerDataSync(conn, data);
                }
                future.complete(data);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load player data for UUID " + uuid, e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private PlayerData queryPlayer(Connection conn, UUID uuid, String fallbackName) throws SQLException {
        String sql = "SELECT last_name, daily_used_seconds, last_reset_period, purchased_seconds, updated_at " +
                "FROM players WHERE uuid = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;

                String name = rs.getString("last_name");
                if (fallbackName != null && !fallbackName.isEmpty()) {
                    name = fallbackName;
                }
                return new PlayerData(
                        uuid,
                        name,
                        rs.getLong("daily_used_seconds"),
                        rs.getLong("last_reset_period"),
                        rs.getLong("purchased_seconds"),
                        rs.getLong("updated_at")
                );
            }
        }
    }

    private void savePlayerDataSync(Connection conn, PlayerData data) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPSERT_PLAYER_SQL)) {
            bindPlayerData(ps, data);
            ps.executeUpdate();
            data.setDirty(false);
        }
    }

    private void bindPlayerData(PreparedStatement ps, PlayerData data) throws SQLException {
        ps.setString(1, data.getUuid().toString());
        ps.setString(2, data.getLastName());
        ps.setLong(3, data.getDailyUsedSeconds());
        ps.setLong(4, data.getLastResetPeriod());
        ps.setLong(5, data.getPurchasedSeconds());
        ps.setLong(6, data.getUpdatedAt());
    }

    public CompletableFuture<Void> savePlayerData(PlayerData data) {
        PlayerData snapshot = data.snapshot();
        CompletableFuture<Void> future = new CompletableFuture<>();
        dbExecutor.submit(() -> {
            try {
                savePlayerDataSync(getConnection(), snapshot);
                future.complete(null);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save player data for UUID " + snapshot.getUuid(), e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public void saveAllPlayers(Collection<PlayerData> players, boolean synchronous) {
        List<PlayerData> snapshots = new ArrayList<>();
        for (PlayerData p : players) {
            if (p.isDirty()) {
                snapshots.add(p.snapshot());
            }
        }

        if (snapshots.isEmpty()) return;

        Runnable task = () -> executeBatchSave(snapshots);
        if (synchronous) {
            task.run();
        } else {
            dbExecutor.submit(task);
        }
    }

    private void executeBatchSave(List<PlayerData> snapshots) {
        try {
            Connection conn = getConnection();
            boolean prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(UPSERT_PLAYER_SQL)) {
                for (PlayerData data : snapshots) {
                    bindPlayerData(ps, data);
                    ps.addBatch();
                }
                ps.executeBatch();
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
    }

    public CompletableFuture<PlayerData> modifyPlayerDataAsync(UUID uuid, String fallbackName, Consumer<PlayerData> modifier) {
        CompletableFuture<PlayerData> future = new CompletableFuture<>();
        dbExecutor.submit(() -> {
            try {
                Connection conn = getConnection();
                PlayerData data = queryPlayer(conn, uuid, fallbackName);
                if (data == null) {
                    data = new PlayerData(uuid, fallbackName, 0L, 0L, 0L, System.currentTimeMillis());
                }

                modifier.accept(data);
                savePlayerDataSync(conn, data);
                future.complete(data);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to modify player data for UUID " + uuid, e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<List<Grant>> loadAllGrants() {
        CompletableFuture<List<Grant>> future = new CompletableFuture<>();
        dbExecutor.submit(() -> {
            List<Grant> list = new ArrayList<>();
            String sql = "SELECT id, type, target, expires_at, created_by, created_at FROM grants;";
            try (PreparedStatement ps = getConnection().prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new Grant(
                            rs.getInt("id"),
                            Grant.GrantType.fromString(rs.getString("type")),
                            rs.getString("target"),
                            rs.getLong("expires_at"),
                            rs.getString("created_by"),
                            rs.getLong("created_at")
                    ));
                }
                future.complete(list);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load grants from database", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<Grant> insertGrant(Grant.GrantType type, String target, long expiresAt, String createdBy) {
        CompletableFuture<Grant> future = new CompletableFuture<>();
        long createdAt = System.currentTimeMillis();
        dbExecutor.submit(() -> {
            String sql = "INSERT INTO grants (type, target, expires_at, created_by, created_at) VALUES (?, ?, ?, ?, ?);";
            try (PreparedStatement ps = getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, type.name());
                ps.setString(2, target);
                ps.setLong(3, expiresAt);
                ps.setString(4, createdBy);
                ps.setLong(5, createdAt);
                ps.executeUpdate();

                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        future.complete(new Grant(keys.getInt(1), type, target, expiresAt, createdBy, createdAt));
                        return;
                    }
                }
                future.completeExceptionally(new SQLException("No ID returned for new grant"));
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to insert grant", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<Boolean> revokeGrant(int grantId) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        dbExecutor.submit(() -> {
            String sql = "DELETE FROM grants WHERE id = ?;";
            try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
                ps.setInt(1, grantId);
                future.complete(ps.executeUpdate() > 0);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to revoke grant #" + grantId, e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<Integer> purgeExpiredGrants() {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        dbExecutor.submit(() -> {
            String sql = "DELETE FROM grants WHERE expires_at <= ?;";
            try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
                ps.setLong(1, System.currentTimeMillis());
                future.complete(ps.executeUpdate());
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to purge expired grants", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public synchronized void close(Collection<PlayerData> onlinePlayers) {
        try {
            saveAllPlayers(onlinePlayers, true);

            dbExecutor.shutdown();
            if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                dbExecutor.shutdownNow();
            }

            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error closing SQLite database", e);
        }
    }
}
