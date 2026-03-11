package gg.jos.josfamily.storage;

import gg.jos.josfamily.model.MarriageRecord;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

public final class SqlMarriageRepository {
    private final DataSource dataSource;
    private final DatabaseType databaseType;

    public SqlMarriageRepository(DataSource dataSource, DatabaseType databaseType) {
        this.dataSource = dataSource;
        this.databaseType = databaseType;
    }

    public void initialize() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS marriages (
                    partner_one VARCHAR(36) NOT NULL,
                    partner_two VARCHAR(36) NOT NULL,
                    married_at BIGINT NOT NULL,
                    PRIMARY KEY (partner_one, partner_two),
                    UNIQUE (partner_one),
                    UNIQUE (partner_two)
                )
                """);
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
}
