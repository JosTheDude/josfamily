package gg.jos.josfamily.command;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.CatchUnknown;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandCompletion;
import co.aikar.commands.annotation.Default;
import co.aikar.commands.annotation.Description;
import co.aikar.commands.annotation.Optional;
import co.aikar.commands.annotation.Subcommand;
import co.aikar.commands.annotation.Syntax;
import co.aikar.commands.bukkit.contexts.OnlinePlayer;
import gg.jos.josfamily.JosFamily;
import gg.jos.josfamily.model.PendingAdoptionRequest;
import gg.jos.josfamily.service.AdoptionService;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

@CommandAlias("adopt")
public final class AdoptCommand extends BaseCommand {
    private final JosFamily plugin;

    public AdoptCommand(JosFamily plugin) {
        this.plugin = plugin;
    }

    @Default
    @CommandCompletion("@players")
    @Syntax("[player]")
    @Description("Send an adoption request or view adoption help")
    public void onDefault(Player player, @Optional OnlinePlayer target) {
        if (target == null) {
            plugin.messages().send(player, "help.adopt");
            return;
        }

        adopt(player, target.getPlayer());
    }

    @Subcommand("accept")
    @CommandCompletion("@players")
    @Syntax("[player]")
    public void onAccept(Player player, @Optional OnlinePlayer parent) {
        plugin.adoptionService().acceptRequest(player, parent == null ? null : parent.getPlayer().getUniqueId());
    }

    @Subcommand("deny")
    @CommandCompletion("@players")
    @Syntax("[player]")
    public void onDeny(Player player, @Optional OnlinePlayer parent) {
        plugin.adoptionService().denyRequest(player, parent == null ? null : parent.getPlayer().getUniqueId());
    }

    @Subcommand("unadopt|disown")
    @CommandCompletion("@players")
    @Syntax("<player>")
    public void onUnadopt(Player player, OnlinePlayer target) {
        plugin.adoptionService().removeAdoption(player, target.getPlayer().getUniqueId());
    }

    @Subcommand("tree")
    @CommandCompletion("@players")
    @Syntax("[player]")
    public void onTree(Player player, @Optional OnlinePlayer target) {
        Player subject = target == null ? player : target.getPlayer();
        if (!player.equals(subject) && !player.hasPermission("josfamily.admin.inspect")) {
            plugin.messages().send(player, "errors.no-permission");
            return;
        }

        for (Component line : plugin.familyTreeService().renderTree(subject.getUniqueId())) {
            player.sendMessage(line);
        }
    }

    @CatchUnknown
    public void onUnknown(Player player) {
        plugin.messages().send(player, "help.adopt");
    }

    private void adopt(Player parent, Player child) {
        if (parent.equals(child)) {
            plugin.messages().send(parent, "adoption.cannot-adopt-self");
            return;
        }

        AdoptionService service = plugin.adoptionService();
        PendingAdoptionRequest request = service.createRequest(parent, child);
        if (request == null) {
            return;
        }

        plugin.marriageUiFactory().openAdoptionConfirmation(child, request);
    }
}
