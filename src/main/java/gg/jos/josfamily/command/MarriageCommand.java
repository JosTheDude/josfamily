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
import gg.jos.josfamily.JosFamily;
import gg.jos.josfamily.config.MessageService;
import gg.jos.josfamily.config.PluginSettings;
import gg.jos.josfamily.model.MarriageRecord;
import gg.jos.josfamily.model.PendingProposal;
import gg.jos.josfamily.service.MarriageService;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
    @Description("Open your status or alias /marry propose [player]")
    public void onDefault(Player player, @Optional String targetName) {
        if (targetName == null || targetName.isBlank()) {
            showStatus(player, player.getUniqueId(), player.getName());
            return;
        }

        onPropose(player, targetName);
    }

    @Subcommand("propose")
    @CommandCompletion("@players")
    @Syntax("<player>")
    @Description("Send a marriage proposal")
    public void onPropose(Player player, String targetName) {
        if (player.getName().equalsIgnoreCase(targetName)) {
            plugin.messages().send(player, "marriage.cannot-marry-self");
            return;
        }

        resolveOnlinePlayer(player, targetName, target ->
            plugin.taskDispatcher().runEntity(player, () -> propose(player, target.playerId(), target.name()))
        );
    }

    @Subcommand("status")
    @Syntax("[player]")
    @CommandCompletion("@players")
    public void onStatus(Player player, @Optional String targetName) {
        if (targetName == null || targetName.isBlank() || player.getName().equalsIgnoreCase(targetName)) {
            showStatus(player, player.getUniqueId(), player.getName());
            return;
        }

        if (!player.hasPermission("josfamily.admin.inspect")) {
            plugin.messages().send(player, "errors.no-permission");
            return;
        }

        resolveOnlinePlayer(player, targetName, target ->
            plugin.taskDispatcher().runEntity(player, () -> showStatus(player, target.playerId(), target.name()))
        );
    }

    @Subcommand("accept")
    @CommandCompletion("@players")
    @Syntax("[player]")
    public void onAccept(Player player, @Optional String proposerName) {
        MarriageService service = plugin.marriageService();
        service.acceptProposal(player, blankToNull(proposerName));
    }

    @Subcommand("deny")
    @CommandCompletion("@players")
    @Syntax("[player]")
    public void onDeny(Player player, @Optional String proposerName) {
        MarriageService service = plugin.marriageService();
        service.denyProposal(player, blankToNull(proposerName));
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

    private void propose(Player proposer, UUID targetId, String targetName) {
        PluginSettings.ProposalDistanceSettings distanceSettings = plugin.settings().proposalDistance();
        if (distanceSettings.enabled()) {
            Location proposerLocation = proposer.getLocation();
            double maxDistanceSquared = distanceSettings.radius() * distanceSettings.radius();
            plugin.taskDispatcher().runGlobal(() -> {
                Player target = Bukkit.getPlayer(targetId);
                if (target == null) {
                    plugin.taskDispatcher().runEntity(
                        proposer,
                        () -> plugin.messages().send(proposer, "errors.player-not-found", Map.of("player", targetName))
                    );
                    return;
                }

                plugin.taskDispatcher().runEntity(target, () -> {
                    boolean withinRange = target.getWorld().equals(proposerLocation.getWorld())
                        && target.getLocation().distanceSquared(proposerLocation) <= maxDistanceSquared;
                    plugin.taskDispatcher().runEntity(proposer, () -> {
                        if (!withinRange) {
                            plugin.messages().send(
                                proposer,
                                "proposal.too-far",
                                Map.of("player", targetName, "radius", formatRadius(distanceSettings.radius()))
                            );
                            return;
                        }

                        submitProposal(proposer, targetId, targetName);
                    });
                });
            });
            return;
        }

        submitProposal(proposer, targetId, targetName);
    }

    private void submitProposal(Player proposer, UUID targetId, String targetName) {
        MarriageService service = plugin.marriageService();
        if (service.tryAcceptReciprocalProposal(proposer, targetId)) {
            return;
        }

        PendingProposal proposal = service.createProposal(proposer, targetId, targetName);
        if (proposal == null) {
            return;
        }

        plugin.marriageUiFactory().openProposalConfirmation(targetId, proposal);
    }

    private String formatRadius(double radius) {
        return radius == Math.rint(radius) ? Long.toString((long) radius) : Double.toString(radius);
    }

    private void showStatus(Player viewer, UUID subjectId, String subjectName) {
        MessageService messages = plugin.messages();
        MarriageRecord marriage = plugin.marriageService().getMarriage(subjectId);
        if (marriage == null) {
            messages.send(viewer, "status.single", Map.of("player", subjectName));
            java.util.Optional<PendingProposal> pending = plugin.marriageService().getIncomingProposal(subjectId);
            pending.ifPresent(proposal -> messages.send(
                viewer,
                "status.pending",
                Map.of("player", subjectName, "partner", proposal.proposerName())
            ));
        } else {
            UUID partnerId = marriage.partnerOf(subjectId);
            OfflinePlayer partner = Bukkit.getOfflinePlayer(partnerId);
            messages.send(
                viewer,
                "status.married",
                Map.of(
                    "player", subjectName,
                    "partner", partner.getName() == null ? partnerId.toString() : partner.getName(),
                    "date", DATE_FORMATTER.format(marriage.marriedAt())
                )
            );
        }
    }

    private void resolveOnlinePlayer(Player requester, String targetName, java.util.function.Consumer<ResolvedPlayer> consumer) {
        plugin.taskDispatcher().runGlobal(() -> {
            Player target = Bukkit.getPlayerExact(targetName);
            if (target == null) {
                plugin.taskDispatcher().runEntity(
                    requester,
                    () -> plugin.messages().send(requester, "errors.player-not-found", Map.of("player", targetName))
                );
                return;
            }

            consumer.accept(new ResolvedPlayer(target.getUniqueId(), target.getName()));
        });
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record ResolvedPlayer(UUID playerId, String name) {}
}
