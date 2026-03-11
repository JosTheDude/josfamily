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
import gg.jos.josfamily.config.MessageService;
import gg.jos.josfamily.model.AdoptionRecord;
import gg.jos.josfamily.model.MarriageRecord;
import gg.jos.josfamily.model.PendingProposal;
import gg.jos.josfamily.service.AdoptionService;
import gg.jos.josfamily.service.MarriageService;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

@CommandAlias("marry|marriage|family")
public final class MarriageCommand extends BaseCommand {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault());

    private final JosFamily plugin;

    public MarriageCommand(JosFamily plugin) {
        this.plugin = plugin;
    }

    @Default
    @CommandCompletion("@players")
    @Syntax("[player]")
    @Description("Open your status or send a marriage proposal")
    public void onDefault(Player player, @Optional OnlinePlayer target) {
        if (target == null) {
            showStatus(player, player);
            return;
        }

        propose(player, target.getPlayer());
    }

    @Subcommand("status")
    @Syntax("[player]")
    @CommandCompletion("@players")
    public void onStatus(Player player, @Optional OnlinePlayer target) {
        Player targetPlayer = target == null ? player : target.getPlayer();
        if (!player.equals(targetPlayer) && !player.hasPermission("josfamily.admin.inspect")) {
            plugin.messages().send(player, "errors.no-permission");
            return;
        }

        showStatus(player, targetPlayer);
    }

    @Subcommand("accept")
    @CommandCompletion("@players")
    @Syntax("[player]")
    public void onAccept(Player player, @Optional OnlinePlayer proposer) {
        MarriageService service = plugin.marriageService();
        service.acceptProposal(player, proposer == null ? null : proposer.getPlayer().getUniqueId());
    }

    @Subcommand("deny")
    @CommandCompletion("@players")
    @Syntax("[player]")
    public void onDeny(Player player, @Optional OnlinePlayer proposer) {
        MarriageService service = plugin.marriageService();
        service.denyProposal(player, proposer == null ? null : proposer.getPlayer().getUniqueId());
    }

    @Subcommand("divorce")
    public void onDivorce(Player player) {
        MarriageRecord marriage = plugin.marriageService().getMarriage(player.getUniqueId());
        if (marriage == null) {
            plugin.messages().send(player, "marriage.not-married");
            return;
        }

        if (plugin.settings().marriage().divorceRequiresConfirmation()) {
            plugin.marriageUiFactory().openDivorceConfirmation(player, marriage);
            return;
        }

        plugin.marriageService().divorce(player);
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

    @Subcommand("reload")
    public void onReload(Player player) {
        if (!player.hasPermission("josfamily.admin.reload")) {
            plugin.messages().send(player, "errors.no-permission");
            return;
        }

        try {
            plugin.reloadPluginState();
            plugin.messages().send(player, "admin.reload-success");
        } catch (Exception exception) {
            plugin.getLogger().severe("Failed to reload JosFamily: " + exception.getMessage());
            plugin.messages().send(player, "admin.reload-failure", Map.of("error", exception.getMessage()));
        }
    }

    @CatchUnknown
    public void onUnknown(Player player) {
        plugin.messages().send(player, "help.player");
    }

    private void propose(Player proposer, Player target) {
        if (proposer.equals(target)) {
            plugin.messages().send(proposer, "marriage.cannot-marry-self");
            return;
        }

        MarriageService service = plugin.marriageService();
        if (service.tryAcceptReciprocalProposal(proposer, target)) {
            return;
        }

        PendingProposal proposal = service.createProposal(proposer, target);
        if (proposal == null) {
            return;
        }

        plugin.marriageUiFactory().openProposalConfirmation(target, proposal);
    }

    private void showStatus(Player viewer, Player subject) {
        MessageService messages = plugin.messages();
        MarriageRecord marriage = plugin.marriageService().getMarriage(subject.getUniqueId());
        if (marriage == null) {
            messages.send(viewer, "status.single", Map.of("player", subject.getName()));
            java.util.Optional<PendingProposal> pending = plugin.marriageService().getIncomingProposal(subject.getUniqueId());
            pending.ifPresent(proposal -> messages.send(
                viewer,
                "status.pending",
                Map.of("player", subject.getName(), "partner", proposal.proposerName())
            ));
        } else {
            UUID partnerId = marriage.partnerOf(subject.getUniqueId());
            OfflinePlayer partner = Bukkit.getOfflinePlayer(partnerId);
            messages.send(
                viewer,
                "status.married",
                Map.of(
                    "player", subject.getName(),
                    "partner", partner.getName() == null ? partnerId.toString() : partner.getName(),
                    "date", DATE_FORMATTER.format(marriage.marriedAt())
                )
            );
        }

        AdoptionService adoptionService = plugin.adoptionService();
        List<AdoptionRecord> parents = adoptionService.parentsOf(subject.getUniqueId());
        if (!parents.isEmpty()) {
            messages.send(
                viewer,
                "status.parents",
                Map.of("players", joinNames(parents.stream().map(record -> record.parentId()).toList()))
            );
        }

        List<AdoptionRecord> children = adoptionService.childrenOf(subject.getUniqueId());
        if (!children.isEmpty()) {
            messages.send(
                viewer,
                "status.children",
                Map.of("players", joinNames(children.stream().map(record -> record.childId()).toList()))
            );
        }

        adoptionService.getIncomingRequest(subject.getUniqueId()).ifPresent(request -> messages.send(
            viewer,
            "status.pending-adoption",
            Map.of("partner", request.parentName())
        ));
    }

    private String joinNames(List<UUID> playerIds) {
        return playerIds.stream()
            .map(Bukkit::getOfflinePlayer)
            .map(this::nameOf)
            .collect(Collectors.joining(", "));
    }

    private String nameOf(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }
}
