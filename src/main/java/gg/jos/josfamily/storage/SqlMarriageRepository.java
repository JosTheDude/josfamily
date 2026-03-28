package gg.jos.josfamily.storage;

import gg.jos.josfamily.model.MarriageRecord;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

public final class SqlMarriageRepository {
    private static final int TARGET_SCHEMA_VERSION = 2;
    private static final String SCHEMA_VERSION_TABLE = "josfamily_schema_version";
    private final DataSource dataSource;
    private final DatabaseType databaseType;

    public SqlMarriageRepository(DataSource dataSource, DatabaseType databaseType) {
        this.dataSource = dataSource;
        this.databaseType = databaseType;
    }

    public void initialize() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                createSchemaVersionTable(connection);

                int currentVersion = readSchemaVersion(connection);
                if (currentVersion == 0) {
                    if (tableExists(connection, "marriages")) {
                        migrateLegacyMarriages(connection);
                    } else {
                        createMarriagesTable(connection, "marriages");
                    }
                    writeSchemaVersion(connection, TARGET_SCHEMA_VERSION);
                } else if (currentVersion < TARGET_SCHEMA_VERSION) {
                    if (currentVersion == 1) {
                        migrateLegacyMarriages(connection);
                        writeSchemaVersion(connection, TARGET_SCHEMA_VERSION);
                    } else {
                        throw new SQLException("Unsupported JosFamily schema version: " + currentVersion);
                    }
                }

                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof SQLException sqlException) {
                    throw sqlException;
                }
                throw new SQLException("Failed to initialize marriages schema", exception);
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    public List<MarriageRecord> loadAll() throws SQLException {
        List<MarriageRecord> marriages = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT partner_one, partner_two, married_at FROM marriages");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                marriages.add(MarriageRecord.normalize(
                    UUID.fromString(resultSet.getString("partner_one")),
                    UUID.fromString(resultSet.getString("partner_two")),
                    Instant.ofEpochMilli(resultSet.getLong("married_at"))
                ));
            }
        }
        return marriages;
    }

    public void insert(MarriageRecord marriage) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO marriages (partner_one, partner_two, married_at) VALUES (?, ?, ?)"
             )) {
            statement.setString(1, marriage.partnerOne().toString());
            statement.setString(2, marriage.partnerTwo().toString());
            statement.setLong(3, marriage.marriedAt().toEpochMilli());
            statement.executeUpdate();
        }
    }

    public void delete(MarriageRecord marriage) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "DELETE FROM marriages WHERE partner_one = ? AND partner_two = ?"
             )) {
            statement.setString(1, marriage.partnerOne().toString());
            statement.setString(2, marriage.partnerTwo().toString());
            statement.executeUpdate();
        }
    }

    private void createSchemaVersionTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS josfamily_schema_version (
                    schema_version INTEGER NOT NULL
                )
                """);
        }
    }

    private int readSchemaVersion(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT schema_version FROM " + SCHEMA_VERSION_TABLE + " LIMIT 1"
        ); ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getInt("schema_version") : 0;
        }
    }

    private void writeSchemaVersion(Connection connection, int version) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + SCHEMA_VERSION_TABLE);
        }

        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO " + SCHEMA_VERSION_TABLE + " (schema_version) VALUES (?)"
        )) {
            statement.setInt(1, version);
            statement.executeUpdate();
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getTables(connection.getCatalog(), null, tableName, new String[]{"TABLE"})) {
            if (resultSet.next()) {
                return true;
            }
        }
        try (ResultSet resultSet = metaData.getTables(connection.getCatalog(), null, tableName.toUpperCase(), new String[]{"TABLE"})) {
            if (resultSet.next()) {
                return true;
            }
        }
        try (ResultSet resultSet = metaData.getTables(connection.getCatalog(), null, tableName.toLowerCase(), new String[]{"TABLE"})) {
            return resultSet.next();
        }
    }

    private void migrateLegacyMarriages(Connection connection) throws SQLException {
        List<MarriageRecord> migratedRecords = normalizeLegacyRows(loadLegacyRows(connection));

        createMarriagesTable(connection, "marriages_v2");
        insertRecords(connection, "marriages_v2", migratedRecords);

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE marriages");
            statement.executeUpdate(renameTableSql("marriages_v2", "marriages"));
        }
    }

    private List<MarriageRecord> loadLegacyRows(Connection connection) throws SQLException {
        List<MarriageRecord> marriages = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT partner_one, partner_two, married_at FROM marriages"
        ); ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                try {
                    marriages.add(MarriageRecord.normalize(
                        UUID.fromString(resultSet.getString("partner_one")),
                        UUID.fromString(resultSet.getString("partner_two")),
                        Instant.ofEpochMilli(resultSet.getLong("married_at"))
                    ));
                } catch (IllegalArgumentException exception) {
                    throw new SQLException("Legacy marriages table contains invalid UUID data", exception);
                }
            }
        }
        return marriages;
    }

    private List<MarriageRecord> normalizeLegacyRows(List<MarriageRecord> legacyRows) throws SQLException {
        Map<String, MarriageRecord> uniquePairs = new HashMap<>();
        for (MarriageRecord marriage : legacyRows) {
            if (marriage.partnerOne().equals(marriage.partnerTwo())) {
                throw new SQLException("Legacy marriages table contains a self-marriage for " + marriage.partnerOne());
            }

            String pairKey = marriage.partnerOne() + ":" + marriage.partnerTwo();
            MarriageRecord existing = uniquePairs.get(pairKey);
            if (existing == null || marriage.marriedAt().isBefore(existing.marriedAt())) {
                uniquePairs.put(pairKey, marriage);
            }
        }

        Map<UUID, MarriageRecord> marriagesByPlayer = new HashMap<>();
        List<MarriageRecord> normalized = new ArrayList<>();
        for (MarriageRecord marriage : uniquePairs.values()) {
            ensureUniquePlayerMarriage(marriagesByPlayer, marriage.partnerOne(), marriage);
            ensureUniquePlayerMarriage(marriagesByPlayer, marriage.partnerTwo(), marriage);
            normalized.add(marriage);
        }
        return normalized;
    }

    private void ensureUniquePlayerMarriage(Map<UUID, MarriageRecord> marriagesByPlayer, UUID playerId, MarriageRecord marriage) throws SQLException {
        MarriageRecord existing = marriagesByPlayer.putIfAbsent(playerId, marriage);
        if (existing != null && !existing.equals(marriage)) {
            throw new SQLException("Legacy marriages table contains conflicting marriages for player " + playerId);
        }
    }

    private void createMarriagesTable(Connection connection, String tableName) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS %s (
                    partner_one VARCHAR(36) NOT NULL,
                    partner_two VARCHAR(36) NOT NULL,
                    married_at BIGINT NOT NULL,
                    PRIMARY KEY (partner_one, partner_two),
                    UNIQUE (partner_one),
                    UNIQUE (partner_two),
                    CHECK (partner_one < partner_two)
                )
                """.formatted(tableName));
        }
    }

    private void insertRecords(Connection connection, String tableName, List<MarriageRecord> marriages) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO " + tableName + " (partner_one, partner_two, married_at) VALUES (?, ?, ?)"
        )) {
            for (MarriageRecord marriage : marriages) {
                statement.setString(1, marriage.partnerOne().toString());
                statement.setString(2, marriage.partnerTwo().toString());
                statement.setLong(3, marriage.marriedAt().toEpochMilli());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private String renameTableSql(String fromTable, String toTable) {
        return switch (databaseType) {
            case SQLITE -> "ALTER TABLE " + fromTable + " RENAME TO " + toTable;
            case MYSQL -> "RENAME TABLE " + fromTable + " TO " + toTable;
        };
    }
}
