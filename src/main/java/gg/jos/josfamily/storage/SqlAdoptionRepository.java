package gg.jos.josfamily.storage;

import gg.jos.josfamily.model.AdoptionRecord;
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

public final class SqlAdoptionRepository {
    private final DataSource dataSource;

    public SqlAdoptionRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void initialize() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS adoptions (
                    parent_id VARCHAR(36) NOT NULL,
                    child_id VARCHAR(36) NOT NULL,
                    adopted_at BIGINT NOT NULL,
                    PRIMARY KEY (parent_id, child_id)
                )
                """);
        }
    }

    public List<AdoptionRecord> loadAll() throws SQLException {
        List<AdoptionRecord> adoptions = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT parent_id, child_id, adopted_at FROM adoptions");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                adoptions.add(new AdoptionRecord(
                    UUID.fromString(resultSet.getString("parent_id")),
                    UUID.fromString(resultSet.getString("child_id")),
                    Instant.ofEpochMilli(resultSet.getLong("adopted_at"))
                ));
            }
        }
        return adoptions;
    }

    public void insert(AdoptionRecord adoption) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO adoptions (parent_id, child_id, adopted_at) VALUES (?, ?, ?)"
             )) {
            statement.setString(1, adoption.parentId().toString());
            statement.setString(2, adoption.childId().toString());
            statement.setLong(3, adoption.adoptedAt().toEpochMilli());
            statement.executeUpdate();
        }
    }

    public void delete(UUID parentId, UUID childId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "DELETE FROM adoptions WHERE parent_id = ? AND child_id = ?"
             )) {
            statement.setString(1, parentId.toString());
            statement.setString(2, childId.toString());
            statement.executeUpdate();
        }
    }
}
