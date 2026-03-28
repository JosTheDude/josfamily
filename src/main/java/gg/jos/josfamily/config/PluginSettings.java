package gg.jos.josfamily.config;

import gg.jos.josfamily.storage.DatabaseType;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

public record PluginSettings(
    DatabaseSettings database,
    MarriageSettings marriage,
    ProposalDistanceSettings proposalDistance,
    MarriageCostSettings marriageCost,
    MarriageRingSettings marriageRing
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

        ProposalDistanceSettings proposalDistance = new ProposalDistanceSettings(
            config.getBoolean("modules.proposal-distance.enabled", true),
            Math.max(0D, config.getDouble("modules.proposal-distance.radius", 8D))
        );

        MarriageCostSettings marriageCost = new MarriageCostSettings(
            config.getBoolean("modules.marriage-cost.enabled", false),
            Math.max(0D, config.getDouble("modules.marriage-cost.amount", 0D)),
            MarriageCostChargeMode.valueOf(config.getString("modules.marriage-cost.charge-mode", "BOTH").toUpperCase()),
            MarriageCostChargeStage.valueOf(config.getString("modules.marriage-cost.proposer-charge-stage", "ACCEPT").toUpperCase()),
            MarriageCostChargeStage.valueOf(config.getString("modules.marriage-cost.target-charge-stage", "ACCEPT").toUpperCase())
        );

        MarriageRingSettings marriageRing = new MarriageRingSettings(
            config.getBoolean("modules.marriage-ring.enabled", true),
            Math.max(0D, config.getDouble("modules.marriage-ring.cost", 500D)),
            resolveMaterial(config.getString("modules.marriage-ring.material", "GOLD_NUGGET")),
            config.getBoolean("modules.marriage-ring.enchanted-glint", true)
        );

        return new PluginSettings(database, marriage, proposalDistance, marriageCost, marriageRing);
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

    public record ProposalDistanceSettings(
        boolean enabled,
        double radius
    ) {}

    public record MarriageCostSettings(
        boolean enabled,
        double amount,
        MarriageCostChargeMode chargeMode,
        MarriageCostChargeStage proposerChargeStage,
        MarriageCostChargeStage targetChargeStage
    ) {
        public boolean active() {
            return enabled && amount > 0D;
        }

        public boolean chargesProposerAt(MarriageCostChargeStage stage) {
            return active() && switch (chargeMode) {
                case PROPOSER, BOTH -> proposerChargeStage == stage;
                case TARGET -> false;
            };
        }

        public boolean chargesTargetAt(MarriageCostChargeStage stage) {
            return active() && switch (chargeMode) {
                case TARGET, BOTH -> targetChargeStage == stage;
                case PROPOSER -> false;
            };
        }
    }

    public record MarriageRingSettings(
        boolean enabled,
        double cost,
        Material material,
        boolean enchantedGlint
    ) {
        public boolean requiresEconomy() {
            return cost > 0D;
        }
    }

    public enum MarriageCostChargeMode {
        PROPOSER,
        TARGET,
        BOTH
    }

    public enum MarriageCostChargeStage {
        SEND,
        ACCEPT
    }

    private static Material resolveMaterial(String input) {
        Material material = Material.matchMaterial(input == null ? "GOLD_NUGGET" : input);
        if (material == null || !material.isItem()) {
            return Material.GOLD_NUGGET;
        }
        return material;
    }
}
