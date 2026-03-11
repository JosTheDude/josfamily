package gg.jos.josfamily.ui;

import gg.jos.josfamily.config.MessageService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public final class UiConfigService {
    private final Map<String, MenuConfig> menus;

    public UiConfigService(FileConfiguration config) {
        this.menus = new HashMap<>();
        cache(config);
    }

    public MenuConfig menu(String key) {
        MenuConfig menu = menus.get(key);
        if (menu == null) {
            throw new IllegalArgumentException("Unknown UI menu: " + key);
        }
        return menu;
    }

    private void cache(FileConfiguration config) {
        ConfigurationSection menusSection = config.getConfigurationSection("menus");
        if (menusSection == null) {
            return;
        }

        for (String menuKey : menusSection.getKeys(false)) {
            ConfigurationSection menuSection = menusSection.getConfigurationSection(menuKey);
            if (menuSection == null) {
                continue;
            }

            menus.put(menuKey, parseMenu(menuSection));
        }
    }

    private MenuConfig parseMenu(ConfigurationSection section) {
        List<String> structure = List.copyOf(section.getStringList("structure"));
        FillConfig fill = parseFill(section.getConfigurationSection("fill"));

        Map<String, ItemConfig> items = new HashMap<>();
        ConfigurationSection itemsSection = section.getConfigurationSection("items");
        if (itemsSection != null) {
            for (String itemKey : itemsSection.getKeys(false)) {
                ConfigurationSection itemSection = itemsSection.getConfigurationSection(itemKey);
                if (itemSection == null) {
                    continue;
                }

                items.put(itemKey, parseItem(itemSection));
            }
        }

        return new MenuConfig(
            section.getString("title", ""),
            structure,
            fill,
            Map.copyOf(items)
        );
    }

    private FillConfig parseFill(ConfigurationSection section) {
        if (section == null || !section.getBoolean("enabled", false)) {
            return null;
        }

        return new FillConfig(
            requiredChar(section.getString("slot-key", "#")),
            material(section.getString("material", "AIR")),
            section.getString("name", " "),
            List.copyOf(section.getStringList("lore"))
        );
    }

    private ItemConfig parseItem(ConfigurationSection section) {
        return new ItemConfig(
            requiredChar(section.getString("slot-key")),
            material(section.getString("material", "STONE")),
            Math.max(1, section.getInt("amount", 1)),
            section.getString("name", ""),
            List.copyOf(section.getStringList("lore"))
        );
    }

    private char requiredChar(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("UI slot-key is missing");
        }

        return input.charAt(0);
    }

    private Material material(String input) {
        Material material = Material.matchMaterial(input);
        return material == null ? Material.STONE : material;
    }

    public record MenuConfig(
        String title,
        List<String> structure,
        FillConfig fill,
        Map<String, ItemConfig> items
    ) {
        public ItemConfig item(String key) {
            ItemConfig item = items.get(key);
            if (item == null) {
                throw new IllegalArgumentException("Unknown menu item: " + key);
            }
            return item;
        }
    }

    public record FillConfig(char slotKey, Material material, String name, List<String> lore) {}

    public record ItemConfig(char slotKey, Material material, int amount, String name, List<String> lore) {
        public xyz.xenondevs.invui.item.ItemBuilder createItemBuilder(MessageService messages, Map<String, String> placeholders) {
            return new xyz.xenondevs.invui.item.ItemBuilder(material, amount)
                .setName(messages.render(name, placeholders))
                .setLore(messages.renderList(lore, placeholders));
        }
    }
}
