package gg.jos.josfamily.config;

import gg.jos.josfamily.scheduler.FoliaTaskDispatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

public final class MessageService {
    private static final Pattern LEGACY_PLACEHOLDER_PATTERN = Pattern.compile("\\{[a-zA-Z0-9_-]+}");
    private static final Pattern MINIMESSAGE_TAG_PATTERN = Pattern.compile("<[/!#a-zA-Z][^\\n>]*>");

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacyAmpersand();
    private final FoliaTaskDispatcher taskDispatcher;
    private final Component prefix;
    private final String legacyPrefix;
    private final Map<String, MessageTemplate> stringTemplates;
    private final Map<String, List<MessageTemplate>> listTemplates;

    public MessageService(FoliaTaskDispatcher taskDispatcher, FileConfiguration config) {
        this.taskDispatcher = taskDispatcher;
        this.prefix = miniMessage.deserialize(config.getString("prefix", "<gray>[<pink>JosFamily</pink>]</gray> "));
        this.legacyPrefix = legacySerializer.serialize(prefix);
        this.stringTemplates = new HashMap<>();
        this.listTemplates = new HashMap<>();
        cache(config);
    }

    public void send(CommandSender sender, String path) {
        send(sender, path, Map.of());
    }

    public void send(CommandSender sender, String path, Map<String, String> placeholders) {
        if (!stringTemplates.containsKey(path)) {
            return;
        }

        sender.sendMessage(component(path, placeholders));
    }

    public Component component(String path, Map<String, String> placeholders) {
        MessageTemplate template = stringTemplates.get(path);
        return template == null ? deserialize(path, placeholders) : deserialize(template, placeholders);
    }

    public List<Component> componentList(String path, Map<String, String> placeholders) {
        List<MessageTemplate> templates = listTemplates.get(path);
        if (templates == null || templates.isEmpty()) {
            return List.of();
        }

        List<Component> resolved = new ArrayList<>(templates.size());
        for (MessageTemplate template : templates) {
            resolved.add(deserialize(template, placeholders));
        }
        return resolved;
    }

    public void broadcast(String path, Map<String, String> placeholders) {
        Component message = component(path, placeholders);
        taskDispatcher.runGlobal(() -> {
            Bukkit.getConsoleSender().sendMessage(message);
            for (Player player : Bukkit.getOnlinePlayers()) {
                taskDispatcher.runEntity(player, () -> player.sendMessage(message));
            }
        });
    }

    public String raw(String path, String fallback) {
        MessageTemplate template = stringTemplates.get(path);
        return template == null ? fallback : template.raw();
    }

    public Component render(String input, Map<String, String> placeholders) {
        return deserialize(input, placeholders);
    }

    public List<Component> renderList(List<String> input, Map<String, String> placeholders) {
        List<Component> resolved = new ArrayList<>(input.size());
        for (String line : input) {
            resolved.add(deserialize(line, placeholders));
        }
        return resolved;
    }

    private Component deserialize(String input, Map<String, String> placeholders) {
        return deserialize(new MessageTemplate(input, detectLegacy(input)), placeholders);
    }

    private Component deserialize(MessageTemplate template, Map<String, String> placeholders) {
        String input = template.raw();
        if (template.legacy()) {
            return legacySerializer.deserialize(applyLegacyPlaceholders(input, placeholders));
        }

        return miniMessage.deserialize(normalizeMiniMessagePlaceholders(input, placeholders), resolver(placeholders));
    }

    private boolean looksLegacy(String input) {
        return detectLegacy(input);
    }

    private boolean detectLegacy(String input) {
        if (MINIMESSAGE_TAG_PATTERN.matcher(input).find()) {
            return false;
        }

        return input.indexOf('&') >= 0 || input.indexOf('§') >= 0 || LEGACY_PLACEHOLDER_PATTERN.matcher(input).find();
    }

    private String applyLegacyPlaceholders(String input, Map<String, String> placeholders) {
        String resolved = input.replace("{prefix}", legacyPrefix);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            resolved = resolved.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return resolved;
    }

    private String normalizeMiniMessagePlaceholders(String input, Map<String, String> placeholders) {
        String resolved = input.replace("{prefix}", "<prefix>");
        for (String key : placeholders.keySet()) {
            resolved = resolved.replace("{" + key + "}", "<" + key + ">");
        }
        return resolved;
    }

    private void cache(FileConfiguration config) {
        for (Map.Entry<String, Object> entry : config.getValues(true).entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String stringValue) {
                stringTemplates.put(entry.getKey(), new MessageTemplate(stringValue, looksLegacy(stringValue)));
                continue;
            }

            if (!(value instanceof List<?> listValue) || listValue.isEmpty()) {
                continue;
            }

            List<MessageTemplate> templates = new ArrayList<>();
            for (Object listEntry : listValue) {
                if (listEntry instanceof String stringEntry) {
                    templates.add(new MessageTemplate(stringEntry, looksLegacy(stringEntry)));
                }
            }

            if (!templates.isEmpty()) {
                listTemplates.put(entry.getKey(), List.copyOf(templates));
            }
        }
    }

    private TagResolver resolver(Map<String, String> placeholders) {
        TagResolver.Builder builder = TagResolver.builder()
            .resolver(Placeholder.component("prefix", prefix));

        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            builder.resolver(Placeholder.unparsed(entry.getKey(), entry.getValue()));
        }

        return builder.build();
    }

    private record MessageTemplate(String raw, boolean legacy) {}
}
