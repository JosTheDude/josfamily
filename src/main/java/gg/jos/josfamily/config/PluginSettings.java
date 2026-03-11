package gg.jos.josfamily.config;

import gg.jos.josfamily.storage.DatabaseType;
import org.bukkit.configuration.file.FileConfiguration;

public record PluginSettings(
    DatabaseSettings database,
    MarriageSettings marriage,
    AdoptionSettings adoption,
    FamilyTreeSettings familyTree,
    MarriageCostSettings marriageCost
) {
    public static PluginSettings from(FileConfiguration config) {
        DatabaseType type = DatabaseType.valueOf(config.getString("database.type", "SQLITE").toUpperCase());
        DatabaseSettings database = new DatabaseSettings(
            type,
            config.getString("database.sqlite.file", "data/marriages.db"),
            config.getString("database.mysql.host", "127.0.0.1"),
            config.getInt("database.mysql.port", 3306),
            config.getString("database.mysql.database", "josfamily"),
            config.getString("database.mysql.username", "root"),
            config.getString("database.mysql.password", ""),
            config.getString("database.mysql.parameters", "useSSL=false&cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048"),
            Math.max(1, config.getInt("database.pool.maximum-pool-size", 8)),
            Math.max(250L, config.getLong("database.pool.connection-timeout-ms", 10000L))
        );

        MarriageSettings marriage = new MarriageSettings(
            Math.max(10L, config.getLong("marriage.proposal-expiry-seconds", 60L)),
            config.getBoolean("marriage.broadcast-marriages", true),
            config.getBoolean("marriage.divorce-requires-confirmation", true)
        );

        AdoptionSettings adoption = new AdoptionSettings(
            Math.max(10L, config.getLong("adoption.request-expiry-seconds", 60L)),
            config.getBoolean("adoption.broadcast-adoptions", true),
            Math.max(1, config.getInt("adoption.max-parents-per-child", 2)),
            Math.max(0, config.getInt("adoption.max-children-per-parent", 0))
        );

        FamilyTreeSettings familyTree = new FamilyTreeSettings(
            Math.max(1, config.getInt("family-tree.max-generations", 4)),
            Math.max(8, config.getInt("family-tree.max-members", 48))
        );

        MarriageCostSettings marriageCost = new MarriageCostSettings(
            config.getBoolean("modules.marriage-cost.enabled", false),
            Math.max(0D, config.getDouble("modules.marriage-cost.amount", 0D)),
            MarriageCostChargeMode.valueOf(config.getString("modules.marriage-cost.charge-mode", "BOTH").toUpperCase())
        );

        return new PluginSettings(database, marriage, adoption, familyTree, marriageCost);
    }

    public record DatabaseSettings(
        DatabaseType type,
        String sqliteFile,
        String mysqlHost,
        int mysqlPort,
        String mysqlDatabase,
        String mysqlUsername,
        String mysqlPassword,
        String mysqlParameters,
        int maximumPoolSize,
        long connectionTimeoutMs
    ) {}

    public record MarriageSettings(
        long proposalExpirySeconds,
        boolean broadcastMarriages,
        boolean divorceRequiresConfirmation
    ) {}

    public record AdoptionSettings(
        long requestExpirySeconds,
        boolean broadcastAdoptions,
        int maxParentsPerChild,
        int maxChildrenPerParent
    ) {
        public boolean hasParentCapacity(int parentCount) {
            return parentCount < maxParentsPerChild;
        }

        public boolean hasChildCapacity(int childCount) {
            return maxChildrenPerParent == 0 || childCount < maxChildrenPerParent;
        }
    }

    public record FamilyTreeSettings(
        int maxGenerations,
        int maxMembers
    ) {}

    public record MarriageCostSettings(
        boolean enabled,
        double amount,
        MarriageCostChargeMode chargeMode
    ) {
        public boolean active() {
            return enabled && amount > 0D;
        }
    }

    public enum MarriageCostChargeMode {
        PROPOSER,
        TARGET,
        BOTH
    }
}
