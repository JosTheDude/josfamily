package gg.jos.josfamily.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import gg.jos.josfamily.JosFamily;
import gg.jos.josfamily.config.PluginSettings.DatabaseSettings;
import java.io.File;

public final class DatabaseFactory {
    private DatabaseFactory() {
    }

    public static ManagedDataSource create(JosFamily plugin, DatabaseSettings settings) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("JosFamily-" + settings.type().name());
        config.setMaximumPoolSize(settings.type() == DatabaseType.SQLITE ? 1 : settings.maximumPoolSize());
        config.setConnectionTimeout(settings.connectionTimeoutMs());
        config.setInitializationFailTimeout(-1L);

        if (settings.type() == DatabaseType.SQLITE) {
            File databaseFile = new File(plugin.getDataFolder(), settings.sqliteFile());
            File parent = databaseFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            config.setDriverClassName("org.sqlite.JDBC");
            config.setJdbcUrl("jdbc:sqlite:" + databaseFile.getAbsolutePath());
            config.addDataSourceProperty("foreign_keys", "true");
        } else {
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
            config.setJdbcUrl(
                "jdbc:mysql://" + settings.mysqlHost() + ":" + settings.mysqlPort() + "/" + settings.mysqlDatabase()
                    + "?" + settings.mysqlParameters()
            );
            config.setUsername(settings.mysqlUsername());
            config.setPassword(settings.mysqlPassword());
        }

        return new ManagedDataSource(new HikariDataSource(config));
    }

    public record ManagedDataSource(HikariDataSource dataSource) implements AutoCloseable {
        @Override
        public void close() {
            dataSource.close();
        }
    }
}
