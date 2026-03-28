package gg.jos.josfamily;

import co.aikar.commands.PaperCommandManager;
import gg.jos.josfamily.command.MarriageCommand;
import gg.jos.josfamily.compat.economy.MarriageCostService;
import gg.jos.josfamily.config.MessageService;
import gg.jos.josfamily.config.PluginSettings;
import gg.jos.josfamily.listener.PlayerConnectionListener;
import gg.jos.josfamily.scheduler.FoliaTaskDispatcher;
import gg.jos.josfamily.service.FamilyTreeService;
import gg.jos.josfamily.service.MarriageService;
import gg.jos.josfamily.storage.DatabaseFactory;
import gg.jos.josfamily.storage.SqlMarriageRepository;
import gg.jos.josfamily.ui.MarriageUiFactory;
import gg.jos.josfamily.ui.UiConfigService;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import xyz.xenondevs.invui.InvUI;

public final class JosFamily extends JavaPlugin {
    private final File messagesFile = new File(getDataFolder(), "messages.yml");
    private final File uiFile = new File(getDataFolder(), "ui.yml");

    private PluginSettings settings;
    private MessageService messages;
    private UiConfigService uiConfig;
    private FoliaTaskDispatcher taskDispatcher;
    private DatabaseFactory.ManagedDataSource managedDataSource;
    private SqlMarriageRepository marriageRepository;
    private MarriageCostService marriageCostService;
    private MarriageService marriageService;
    private FamilyTreeService familyTreeService;
    private MarriageUiFactory marriageUiFactory;
    private PaperCommandManager commandManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);
        saveResource("ui.yml", false);

        taskDispatcher = new FoliaTaskDispatcher(this);
        InvUI.getInstance().setPlugin(this);

        try {
            reloadPluginState();
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Failed to start JosFamily", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        registerCommands();
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
    }

    @Override
    public void onDisable() {
        if (marriageService != null) {
            marriageService.shutdown();
        }
        closeDataSource();
    }

    public synchronized void reloadPluginState() throws SQLException {
        reloadConfig();
        settings = PluginSettings.from(getConfig());
        messages = loadMessages();
        uiConfig = loadUiConfig();

        if (marriageService != null) {
            marriageService.shutdown();
        }
        closeDataSource();
        managedDataSource = DatabaseFactory.create(this, settings.database());
        marriageRepository = new SqlMarriageRepository(managedDataSource.dataSource(), settings.database().type());
        marriageRepository.initialize();

        marriageCostService = MarriageCostService.create(this, settings.marriageCost(), messages);
        marriageService = new MarriageService(this, taskDispatcher, settings, messages, marriageRepository, marriageCostService);
        marriageService.initialize();
        familyTreeService = new FamilyTreeService(marriageService);
        marriageUiFactory = new MarriageUiFactory(this, uiConfig);
    }

    private void registerCommands() {
        commandManager = new PaperCommandManager(this);
        commandManager.registerCommand(new MarriageCommand(this));
    }

    private void closeDataSource() {
        if (managedDataSource != null) {
            managedDataSource.close();
            managedDataSource = null;
        }
    }

    private MessageService loadMessages() {
        return new MessageService(taskDispatcher, loadYamlWithDefaults("messages.yml", messagesFile));
    }

    private UiConfigService loadUiConfig() {
        return new UiConfigService(loadYamlWithDefaults("ui.yml", uiFile));
    }

    private YamlConfiguration loadYamlWithDefaults(String resourceName, File file) {
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        if (getResource(resourceName) == null) {
            throw new IllegalStateException("Bundled " + resourceName + " was not found");
        }

        try (InputStreamReader reader = new InputStreamReader(getResource(resourceName), StandardCharsets.UTF_8)) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(reader);
            configuration.setDefaults(defaults);
            configuration.options().copyDefaults(true);
            configuration.save(file);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load " + resourceName, exception);
        }

        return configuration;
    }

    public PluginSettings settings() {
        return settings;
    }

    public MessageService messages() {
        return messages;
    }

    public UiConfigService uiConfig() {
        return uiConfig;
    }

    public FoliaTaskDispatcher taskDispatcher() {
        return taskDispatcher;
    }

    public MarriageService marriageService() {
        return marriageService;
    }

    public FamilyTreeService familyTreeService() {
        return familyTreeService;
    }

    public MarriageUiFactory marriageUiFactory() {
        return marriageUiFactory;
    }

    public MarriageCostService marriageCostService() {
        return marriageCostService;
    }
}
