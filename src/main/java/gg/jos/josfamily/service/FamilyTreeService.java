package gg.jos.josfamily.service;

import gg.jos.josfamily.config.MessageService;
import gg.jos.josfamily.config.PluginSettings;
import gg.jos.josfamily.model.AdoptionRecord;
import gg.jos.josfamily.model.FamilyTreeView;
import gg.jos.josfamily.model.MarriageRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

public final class FamilyTreeService {
    private final PluginSettings settings;
    private final MessageService messages;
    private final MarriageService marriageService;
    private final AdoptionService adoptionService;

    public FamilyTreeService(
        PluginSettings settings,
        MessageService messages,
        MarriageService marriageService,
        AdoptionService adoptionService
    ) {
        this.settings = settings;
        this.messages = messages;
        this.marriageService = marriageService;
        this.adoptionService = adoptionService;
    }

    public FamilyTreeView buildTree(UUID focusId) {
        NameCache names = new NameCache();
        TraversalBudget budget = new TraversalBudget(settings.familyTree().maxMembers(), focusId);

        List<FamilyTreeView.FamilyTreeLayer> ancestors = collectAncestorLayers(focusId, names, budget);
        List<String> siblings = collectSiblingLabels(focusId, names, budget);
        List<FamilyTreeView.FamilyTreeLayer> descendants = collectDescendantLayers(focusId, names, budget);

        return new FamilyTreeView(
            focusId,
            relationshipLabel(focusId, names),
            List.copyOf(ancestors),
            List.copyOf(siblings),
            List.copyOf(descendants),
            budget.truncated()
        );
    }

    public List<Component> renderTree(UUID focusId) {
        FamilyTreeView tree = buildTree(focusId);
        String focusName = plainName(focusId);
        if (!tree.hasRelationships()) {
            return List.of(messages.component("tree.empty", Map.of("player", focusName)));
        }

        List<Component> lines = new ArrayList<>();
        lines.add(messages.component("tree.title", Map.of("player", focusName)));

        List<FamilyTreeView.FamilyTreeLayer> ancestors = new ArrayList<>(tree.ancestorLayers());
        Collections.reverse(ancestors);
        for (FamilyTreeView.FamilyTreeLayer layer : ancestors) {
            lines.add(messages.component("tree.line", Map.of(
                "label", layer.label(),
                "members", String.join(" | ", layer.members())
            )));
        }

        lines.add(messages.component("tree.line", Map.of("label", "Focus", "members", tree.focusLabel())));

        if (!tree.siblingLabels().isEmpty()) {
            lines.add(messages.component("tree.line", Map.of(
                "label", "Siblings",
                "members", String.join(" | ", tree.siblingLabels())
            )));
        }

        for (FamilyTreeView.FamilyTreeLayer layer : tree.descendantLayers()) {
            lines.add(messages.component("tree.line", Map.of(
                "label", layer.label(),
                "members", String.join(" | ", layer.members())
            )));
        }

        if (tree.truncated()) {
            lines.add(messages.component("tree.truncated", Map.of(
                "max_generations", String.valueOf(settings.familyTree().maxGenerations()),
                "max_members", String.valueOf(settings.familyTree().maxMembers())
            )));
        }

        return List.copyOf(lines);
    }

    private List<FamilyTreeView.FamilyTreeLayer> collectAncestorLayers(UUID focusId, NameCache names, TraversalBudget budget) {
        List<FamilyTreeView.FamilyTreeLayer> layers = new ArrayList<>();
        LinkedHashSet<UUID> current = new LinkedHashSet<>();
        current.add(focusId);

        for (int generation = 1; generation <= settings.familyTree().maxGenerations(); generation++) {
            LinkedHashSet<UUID> next = new LinkedHashSet<>();
            for (UUID memberId : current) {
                for (AdoptionRecord adoption : adoptionService.parentsOf(memberId)) {
                    if (budget.tryInclude(adoption.parentId())) {
                        next.add(adoption.parentId());
                    }
                }
            }

            if (next.isEmpty()) {
                break;
            }

            layers.add(new FamilyTreeView.FamilyTreeLayer(ancestorLabel(generation), describeMembers(next, names)));
            current = next;
        }

        return layers;
    }

    private List<FamilyTreeView.FamilyTreeLayer> collectDescendantLayers(UUID focusId, NameCache names, TraversalBudget budget) {
        List<FamilyTreeView.FamilyTreeLayer> layers = new ArrayList<>();
        LinkedHashSet<UUID> current = new LinkedHashSet<>();
        current.add(focusId);

        for (int generation = 1; generation <= settings.familyTree().maxGenerations(); generation++) {
            LinkedHashSet<UUID> next = new LinkedHashSet<>();
            for (UUID memberId : current) {
                for (AdoptionRecord adoption : adoptionService.childrenOf(memberId)) {
                    if (budget.tryInclude(adoption.childId())) {
                        next.add(adoption.childId());
                    }
                }
            }

            if (next.isEmpty()) {
                break;
            }

            layers.add(new FamilyTreeView.FamilyTreeLayer(descendantLabel(generation), describeMembers(next, names)));
            current = next;
        }

        return layers;
    }

    private List<String> collectSiblingLabels(UUID focusId, NameCache names, TraversalBudget budget) {
        LinkedHashSet<UUID> siblings = new LinkedHashSet<>();
        for (AdoptionRecord parentLink : adoptionService.parentsOf(focusId)) {
            for (AdoptionRecord siblingLink : adoptionService.childrenOf(parentLink.parentId())) {
                UUID siblingId = siblingLink.childId();
                if (!siblingId.equals(focusId) && budget.tryInclude(siblingId)) {
                    siblings.add(siblingId);
                }
            }
        }
        return describeMembers(siblings, names);
    }

    private List<String> describeMembers(Set<UUID> memberIds, NameCache names) {
        if (memberIds.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<UUID> remaining = new LinkedHashSet<>(memberIds);
        List<String> labels = new ArrayList<>(memberIds.size());
        while (!remaining.isEmpty()) {
            UUID memberId = remaining.iterator().next();
            remaining.remove(memberId);

            MarriageRecord marriage = marriageService.getMarriage(memberId);
            if (marriage != null) {
                UUID partnerId = marriage.partnerOf(memberId);
                if (remaining.remove(partnerId)) {
                    labels.add(coupleLabel(memberId, partnerId, names));
                    continue;
                }
            }

            labels.add(relationshipLabel(memberId, names));
        }

        return List.copyOf(labels);
    }

    private String relationshipLabel(UUID playerId, NameCache names) {
        MarriageRecord marriage = marriageService.getMarriage(playerId);
        if (marriage == null) {
            return names.nameOf(playerId);
        }

        return coupleLabel(playerId, marriage.partnerOf(playerId), names);
    }

    private String coupleLabel(UUID firstPlayerId, UUID secondPlayerId, NameCache names) {
        return names.nameOf(firstPlayerId) + " + " + names.nameOf(secondPlayerId);
    }

    private String plainName(UUID playerId) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        return player.getName() == null ? playerId.toString() : player.getName();
    }

    private String ancestorLabel(int generation) {
        return switch (generation) {
            case 1 -> "Parents";
            case 2 -> "Grandparents";
            default -> "Ancestors G" + generation;
        };
    }

    private String descendantLabel(int generation) {
        return switch (generation) {
            case 1 -> "Children";
            case 2 -> "Grandchildren";
            default -> "Descendants G" + generation;
        };
    }

    private static final class TraversalBudget {
        private final int maxMembers;
        private final Set<UUID> visited = new LinkedHashSet<>();
        private boolean truncated;

        private TraversalBudget(int maxMembers, UUID focusId) {
            this.maxMembers = maxMembers;
            visited.add(focusId);
        }

        private boolean tryInclude(UUID playerId) {
            if (visited.contains(playerId)) {
                return true;
            }
            if (visited.size() >= maxMembers) {
                truncated = true;
                return false;
            }

            visited.add(playerId);
            return true;
        }

        private boolean truncated() {
            return truncated;
        }
    }

    private static final class NameCache {
        private final Map<UUID, String> namesById = new LinkedHashMap<>();

        private String nameOf(UUID playerId) {
            return namesById.computeIfAbsent(playerId, id -> {
                OfflinePlayer player = Bukkit.getOfflinePlayer(id);
                return player.getName() == null ? id.toString() : player.getName();
            });
        }
    }
}
