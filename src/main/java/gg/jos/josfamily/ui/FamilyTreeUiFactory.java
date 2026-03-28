package gg.jos.josfamily.ui;

import gg.jos.josfamily.JosFamily;
import gg.jos.josfamily.model.FamilyMapView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.window.Window;

public final class FamilyTreeUiFactory {
    private static final int PARENT_PAGE_SIZE = 2;
    private static final int SIBLING_PAGE_SIZE = 2;
    private static final int CHILD_PAGE_SIZE = 3;
    private static final char[] PARENT_SLOTS = {'p', 'q'};
    private static final char[] SIBLING_SLOTS = {'s', 't'};
    private static final char[] CHILD_SLOTS = {'c', 'd', 'g'};

    private final JosFamily plugin;
    private final UiConfigService uiConfig;

    public FamilyTreeUiFactory(JosFamily plugin, UiConfigService uiConfig) {
        this.plugin = plugin;
        this.uiConfig = uiConfig;
    }

    public void openExplorer(Player viewer, UUID focusId) {
        openExplorer(viewer, focusId, List.of(), 0, 0, 0);
    }

    private void openExplorer(
        Player viewer,
        UUID focusId,
        List<UUID> history,
        int parentPage,
        int siblingPage,
        int childPage
    ) {
        plugin.taskDispatcher().runEntity(viewer, () -> {
            UiConfigService.MenuConfig menu = uiConfig.menu("family-tree");
            FamilyMapView map = plugin.familyTreeService().buildMap(focusId);
            Map<String, String> titlePlaceholders = Map.of("player", map.focus().name());

            Gui.Builder<?, ?> guiBuilder = Gui.builder().setStructure(menu.structure().toArray(String[]::new));
            applyFill(guiBuilder, menu, titlePlaceholders);

            addStaticItems(guiBuilder, menu, viewer, map, history, parentPage, siblingPage, childPage);
            addPagedMembers(guiBuilder, menu, PARENT_SLOTS, map.parents(), parentPage, PARENT_PAGE_SIZE, "parent", viewer, focusId, history);
            addPagedMembers(guiBuilder, menu, SIBLING_SLOTS, map.siblings(), siblingPage, SIBLING_PAGE_SIZE, "sibling", viewer, focusId, history);
            addPagedMembers(guiBuilder, menu, CHILD_SLOTS, map.children(), childPage, CHILD_PAGE_SIZE, "child", viewer, focusId, history);

            Gui gui = guiBuilder.build();

            Window.builder()
                .setViewer(viewer)
                .setTitle(plugin.messages().render(menu.title(), titlePlaceholders))
                .setUpperGui(gui)
                .build()
                .open();
        });
    }

    private void addStaticItems(
        Gui.Builder<?, ?> guiBuilder,
        UiConfigService.MenuConfig menu,
        Player viewer,
        FamilyMapView map,
        List<UUID> history,
        int parentPage,
        int siblingPage,
        int childPage
    ) {
        guiBuilder.addIngredient(
            menu.item("focus").slotKey(),
            Item.simple(menu.item("focus").createItemBuilder(plugin.messages(), Map.of(
                "player", map.focus().name(),
                "parents", String.valueOf(map.parents().size()),
                "siblings", String.valueOf(map.siblings().size()),
                "children", String.valueOf(map.children().size()),
                "spouse", map.spouse() == null ? "-" : map.spouse().name()
            )))
        );

        guiBuilder.addIngredient(
            menu.item("close").slotKey(),
            Item.builder()
                .setItemProvider(menu.item("close").createItemBuilder(plugin.messages(), Map.of()))
                .addClickHandler(click -> viewer.closeInventory())
                .build()
        );

        guiBuilder.addIngredient(
            menu.item("back").slotKey(),
            history.isEmpty()
                ? emptyItem(menu, "empty")
                : Item.builder()
                    .setItemProvider(menu.item("back").createItemBuilder(plugin.messages(), Map.of(
                        "player", plugin.familyTreeService().buildMap(history.get(history.size() - 1)).focus().name()
                    )))
                    .addClickHandler(click -> {
                        List<UUID> nextHistory = history.subList(0, history.size() - 1);
                        openExplorer(viewer, history.get(history.size() - 1), List.copyOf(nextHistory), 0, 0, 0);
                    })
                    .build()
        );

        guiBuilder.addIngredient(
            menu.item("spouse").slotKey(),
            map.spouse() == null
                ? emptyItem(menu, "empty")
                : createMemberItem(menu, "spouse", map.spouse(), viewer, map.focus().playerId(), history)
        );

        guiBuilder.addIngredient(
            menu.item("parents-prev").slotKey(),
            navigationItem(menu, "parents-prev", parentPage, map.parents().size(), PARENT_PAGE_SIZE, viewer, map.focus().playerId(), history, parentPage - 1, siblingPage, childPage, "parents")
        );
        guiBuilder.addIngredient(
            menu.item("parents-next").slotKey(),
            navigationItem(menu, "parents-next", parentPage, map.parents().size(), PARENT_PAGE_SIZE, viewer, map.focus().playerId(), history, parentPage + 1, siblingPage, childPage, "parents")
        );
        guiBuilder.addIngredient(
            menu.item("siblings-prev").slotKey(),
            navigationItem(menu, "siblings-prev", siblingPage, map.siblings().size(), SIBLING_PAGE_SIZE, viewer, map.focus().playerId(), history, parentPage, siblingPage - 1, childPage, "siblings")
        );
        guiBuilder.addIngredient(
            menu.item("siblings-next").slotKey(),
            navigationItem(menu, "siblings-next", siblingPage, map.siblings().size(), SIBLING_PAGE_SIZE, viewer, map.focus().playerId(), history, parentPage, siblingPage + 1, childPage, "siblings")
        );
        guiBuilder.addIngredient(
            menu.item("children-prev").slotKey(),
            navigationItem(menu, "children-prev", childPage, map.children().size(), CHILD_PAGE_SIZE, viewer, map.focus().playerId(), history, parentPage, siblingPage, childPage - 1, "children")
        );
        guiBuilder.addIngredient(
            menu.item("children-next").slotKey(),
            navigationItem(menu, "children-next", childPage, map.children().size(), CHILD_PAGE_SIZE, viewer, map.focus().playerId(), history, parentPage, siblingPage, childPage + 1, "children")
        );
    }

    private void addPagedMembers(
        Gui.Builder<?, ?> guiBuilder,
        UiConfigService.MenuConfig menu,
        char[] slots,
        List<FamilyMapView.FamilyMemberView> members,
        int page,
        int pageSize,
        String itemKey,
        Player viewer,
        UUID focusId,
        List<UUID> history
    ) {
        int start = page * pageSize;
        for (int slotIndex = 0; slotIndex < slots.length; slotIndex++) {
            int memberIndex = start + slotIndex;
            Item item = memberIndex < members.size()
                ? createMemberItem(menu, itemKey, members.get(memberIndex), viewer, focusId, history)
                : emptyItem(menu, "empty");
            guiBuilder.addIngredient(slots[slotIndex], item);
        }
    }

    private Item createMemberItem(
        UiConfigService.MenuConfig menu,
        String itemKey,
        FamilyMapView.FamilyMemberView member,
        Player viewer,
        UUID currentFocusId,
        List<UUID> history
    ) {
        return Item.builder()
            .setItemProvider(menu.item(itemKey).createItemBuilder(plugin.messages(), Map.of("player", member.name())))
            .addClickHandler(click -> {
                List<UUID> nextHistory = new ArrayList<>(history);
                nextHistory.add(currentFocusId);
                openExplorer(viewer, member.playerId(), List.copyOf(nextHistory), 0, 0, 0);
            })
            .build();
    }

    private Item navigationItem(
        UiConfigService.MenuConfig menu,
        String itemKey,
        int currentPage,
        int totalEntries,
        int pageSize,
        Player viewer,
        UUID focusId,
        List<UUID> history,
        int nextParentPage,
        int nextSiblingPage,
        int nextChildPage,
        String group
    ) {
        int pageCount = Math.max(1, (int) Math.ceil((double) totalEntries / pageSize));
        boolean available = currentPage >= 0
            && currentPage < pageCount
            && switch (itemKey) {
                case "parents-prev", "siblings-prev", "children-prev" -> currentPage > 0;
                default -> currentPage + 1 < pageCount;
            };

        if (!available) {
            return emptyItem(menu, "empty");
        }

        int displayPage = switch (itemKey) {
            case "parents-prev", "siblings-prev", "children-prev" -> currentPage;
            default -> currentPage + 2;
        };

        return Item.builder()
            .setItemProvider(menu.item(itemKey).createItemBuilder(plugin.messages(), Map.of(
                "group", group,
                "page", String.valueOf(displayPage),
                "pages", String.valueOf(pageCount)
            )))
            .addClickHandler(click -> openExplorer(
                viewer,
                focusId,
                history,
                Math.max(0, nextParentPage),
                Math.max(0, nextSiblingPage),
                Math.max(0, nextChildPage)
            ))
            .build();
    }

    private Item emptyItem(UiConfigService.MenuConfig menu, String itemKey) {
        return Item.simple(menu.item(itemKey).createItemBuilder(plugin.messages(), Map.of()));
    }

    private void applyFill(Gui.Builder<?, ?> guiBuilder, UiConfigService.MenuConfig menu, Map<String, String> placeholders) {
        UiConfigService.FillConfig fill = menu.fill();
        if (fill == null) {
            return;
        }

        guiBuilder.addIngredient(fill.slotKey(), Item.simple(
            new xyz.xenondevs.invui.item.ItemBuilder(fill.material())
                .setName(plugin.messages().render(fill.name(), placeholders))
                .setLore(plugin.messages().renderList(fill.lore(), placeholders))
        ));
    }
}
