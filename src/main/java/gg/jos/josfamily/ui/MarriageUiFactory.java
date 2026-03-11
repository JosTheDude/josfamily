package gg.jos.josfamily.ui;

import gg.jos.josfamily.JosFamily;
import gg.jos.josfamily.model.MarriageRecord;
import gg.jos.josfamily.model.PendingAdoptionRequest;
import gg.jos.josfamily.model.PendingProposal;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.window.Window;

public final class MarriageUiFactory {
    private final JosFamily plugin;
    private final UiConfigService uiConfig;

    public MarriageUiFactory(JosFamily plugin, UiConfigService uiConfig) {
        this.plugin = plugin;
        this.uiConfig = uiConfig;
    }

    public void openProposalConfirmation(Player target, PendingProposal proposal) {
        plugin.taskDispatcher().runEntity(target, () -> {
            UiConfigService.MenuConfig menu = uiConfig.menu("proposal");
            Map<String, String> placeholders = Map.of(
                "player", proposal.proposerName(),
                "cost", plugin.marriageCostService().displayAmount()
            );

            Item info = Item.simple(menu.item("info").createItemBuilder(plugin.messages(), placeholders));
            Item accept = Item.builder()
                .setItemProvider(menu.item("accept").createItemBuilder(plugin.messages(), placeholders))
                .addClickHandler(click -> {
                    target.closeInventory();
                    plugin.marriageService().acceptProposal(target, proposal.proposerId());
                })
                .build();
            Item deny = Item.builder()
                .setItemProvider(menu.item("deny").createItemBuilder(plugin.messages(), placeholders))
                .addClickHandler(click -> {
                    target.closeInventory();
                    plugin.marriageService().denyProposal(target, proposal.proposerId());
                })
                .build();

            Gui.Builder<?, ?> guiBuilder = Gui.builder().setStructure(menu.structure().toArray(String[]::new));
            applyFill(guiBuilder, menu, placeholders);
            guiBuilder
                .addIngredient(menu.item("info").slotKey(), info)
                .addIngredient(menu.item("accept").slotKey(), accept)
                .addIngredient(menu.item("deny").slotKey(), deny);

            Gui gui = guiBuilder.build();

            Window.builder()
                .setViewer(target)
                .setTitle(plugin.messages().render(menu.title(), placeholders))
                .setUpperGui(gui)
                .build()
                .open();
        });
    }

    public void openDivorceConfirmation(Player player, MarriageRecord marriage) {
        OfflinePlayer partner = Bukkit.getOfflinePlayer(marriage.partnerOf(player.getUniqueId()));
        String partnerName = partner.getName() == null ? partner.getUniqueId().toString() : partner.getName();

        plugin.taskDispatcher().runEntity(player, () -> {
            UiConfigService.MenuConfig menu = uiConfig.menu("divorce");
            Map<String, String> placeholders = Map.of("player", partnerName);

            Item info = Item.simple(menu.item("info").createItemBuilder(plugin.messages(), placeholders));
            Item confirm = Item.builder()
                .setItemProvider(menu.item("confirm").createItemBuilder(plugin.messages(), placeholders))
                .addClickHandler(click -> {
                    player.closeInventory();
                    plugin.marriageService().divorce(player);
                })
                .build();
            Item cancel = Item.builder()
                .setItemProvider(menu.item("cancel").createItemBuilder(plugin.messages(), placeholders))
                .addClickHandler(click -> player.closeInventory())
                .build();

            Gui.Builder<?, ?> guiBuilder = Gui.builder().setStructure(menu.structure().toArray(String[]::new));
            applyFill(guiBuilder, menu, placeholders);
            guiBuilder
                .addIngredient(menu.item("info").slotKey(), info)
                .addIngredient(menu.item("confirm").slotKey(), confirm)
                .addIngredient(menu.item("cancel").slotKey(), cancel);

            Gui gui = guiBuilder.build();

            Window.builder()
                .setViewer(player)
                .setTitle(plugin.messages().render(menu.title(), placeholders))
                .setUpperGui(gui)
                .build()
                .open();
        });
    }

    public void openAdoptionConfirmation(Player child, PendingAdoptionRequest request) {
        plugin.taskDispatcher().runEntity(child, () -> {
            UiConfigService.MenuConfig menu = uiConfig.menu("adoption");
            Map<String, String> placeholders = Map.of("player", request.parentName());

            Item info = Item.simple(menu.item("info").createItemBuilder(plugin.messages(), placeholders));
            Item accept = Item.builder()
                .setItemProvider(menu.item("accept").createItemBuilder(plugin.messages(), placeholders))
                .addClickHandler(click -> {
                    child.closeInventory();
                    plugin.adoptionService().acceptRequest(child, request.parentId());
                })
                .build();
            Item deny = Item.builder()
                .setItemProvider(menu.item("deny").createItemBuilder(plugin.messages(), placeholders))
                .addClickHandler(click -> {
                    child.closeInventory();
                    plugin.adoptionService().denyRequest(child, request.parentId());
                })
                .build();

            Gui.Builder<?, ?> guiBuilder = Gui.builder().setStructure(menu.structure().toArray(String[]::new));
            applyFill(guiBuilder, menu, placeholders);
            guiBuilder
                .addIngredient(menu.item("info").slotKey(), info)
                .addIngredient(menu.item("accept").slotKey(), accept)
                .addIngredient(menu.item("deny").slotKey(), deny);

            Gui gui = guiBuilder.build();

            Window.builder()
                .setViewer(child)
                .setTitle(plugin.messages().render(menu.title(), placeholders))
                .setUpperGui(gui)
                .build()
                .open();
        });
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
