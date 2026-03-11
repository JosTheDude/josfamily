package gg.jos.josfamily.service;

import gg.jos.josfamily.JosFamily;
import gg.jos.josfamily.compat.economy.MarriageCostService;
import gg.jos.josfamily.config.MessageService;
import gg.jos.josfamily.config.PluginSettings;
import gg.jos.josfamily.model.MarriageRecord;
import gg.jos.josfamily.model.PendingProposal;
import gg.jos.josfamily.scheduler.FoliaTaskDispatcher;
import gg.jos.josfamily.storage.SqlMarriageRepository;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public final class MarriageService {
    private final JosFamily plugin;
    private final FoliaTaskDispatcher taskDispatcher;
    private final PluginSettings settings;
    private final MessageService messages;
    private final SqlMarriageRepository repository;
    private final MarriageCostService marriageCostService;
    private final ConcurrentMap<UUID, MarriageRecord> marriagesByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, PendingProposal> proposalsByTarget = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, UUID> targetByProposer = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ScheduledTask> expiryTasks = new ConcurrentHashMap<>();

    public MarriageService(
        JosFamily plugin,
        FoliaTaskDispatcher taskDispatcher,
        PluginSettings settings,
        MessageService messages,
        SqlMarriageRepository repository,
        MarriageCostService marriageCostService
    ) {
        this.plugin = plugin;
        this.taskDispatcher = taskDispatcher;
        this.settings = settings;
        this.messages = messages;
        this.repository = repository;
        this.marriageCostService = marriageCostService;
    }

    public void initialize() throws SQLException {
        marriagesByPlayer.clear();
        for (MarriageRecord marriage : repository.loadAll()) {
            marriagesByPlayer.put(marriage.partnerOne(), marriage);
            marriagesByPlayer.put(marriage.partnerTwo(), marriage);
        }
    }

    public void shutdown() {
        proposalsByTarget.clear();
        targetByProposer.clear();
        expiryTasks.values().forEach(ScheduledTask::cancel);
        expiryTasks.clear();
    }

    public MarriageRecord getMarriage(UUID playerId) {
        return marriagesByPlayer.get(playerId);
    }

    public Optional<PendingProposal> getIncomingProposal(UUID targetId) {
        return Optional.ofNullable(proposalsByTarget.get(targetId));
    }

    public PendingProposal createProposal(Player proposer, Player target) {
        if (getMarriage(proposer.getUniqueId()) != null) {
            messages.send(proposer, "marriage.already-married");
            return null;
        }
        if (getMarriage(target.getUniqueId()) != null) {
            messages.send(
                proposer,
                "marriage.target-married",
                Map.of("player", target.getName())
            );
            return null;
        }
        if (targetByProposer.containsKey(proposer.getUniqueId())) {
            messages.send(proposer, "proposal.already-sent");
            return null;
        }
        if (proposalsByTarget.containsKey(target.getUniqueId())) {
            messages.send(
                proposer,
                "proposal.target-busy",
                Map.of("player", target.getName())
            );
            return null;
        }

        Instant now = Instant.now();
        PendingProposal proposal = new PendingProposal(
            proposer.getUniqueId(),
            proposer.getName(),
            target.getUniqueId(),
            target.getName(),
            now,
            now.plusSeconds(settings.marriage().proposalExpirySeconds())
        );

        proposalsByTarget.put(target.getUniqueId(), proposal);
        targetByProposer.put(proposer.getUniqueId(), target.getUniqueId());

        ScheduledTask expiryTask = taskDispatcher.runAsyncLater(
            settings.marriage().proposalExpirySeconds(),
            TimeUnit.SECONDS,
            () -> expireProposal(target.getUniqueId(), true)
        );
        expiryTasks.put(target.getUniqueId(), expiryTask);

        messages.send(
            proposer,
            "proposal.sent",
            proposalPlaceholders(target.getName())
        );
        messages.send(
            target,
            "proposal.received",
            proposalPlaceholders(proposer.getName())
        );
        return proposal;
    }

    public boolean tryAcceptReciprocalProposal(Player proposer, Player target) {
        PendingProposal incoming = proposalsByTarget.get(proposer.getUniqueId());
        if (incoming == null || !incoming.proposerId().equals(target.getUniqueId())) {
            return false;
        }

        acceptProposal(proposer, target.getUniqueId());
        return true;
    }

    public void acceptProposal(Player target, UUID proposerId) {
        PendingProposal proposal = proposalsByTarget.get(target.getUniqueId());
        if (proposal == null) {
            messages.send(target, "proposal.none");
            return;
        }
        if (proposerId != null && !proposal.proposerId().equals(proposerId)) {
            messages.send(target, "proposal.none-from-player");
            return;
        }

        Player proposer = Bukkit.getPlayer(proposal.proposerId());
        if (proposer == null || !proposer.isOnline()) {
            clearPendingState(target.getUniqueId(), false);
            messages.send(target, "proposal.proposer-offline");
            return;
        }
        if (getMarriage(target.getUniqueId()) != null || getMarriage(proposal.proposerId()) != null) {
            clearPendingState(target.getUniqueId(), false);
            messages.send(target, "marriage.already-married");
            return;
        }

        clearPendingState(target.getUniqueId(), false);
        taskDispatcher.runGlobal(() -> {
            Player onlineTarget = Bukkit.getPlayer(proposal.targetId());
            Player onlineProposer = Bukkit.getPlayer(proposal.proposerId());
            if (onlineTarget == null || onlineProposer == null || !onlineTarget.isOnline() || !onlineProposer.isOnline()) {
                runOnlinePlayerTask(proposal.targetId(), player -> messages.send(player, "proposal.proposer-offline"));
                return;
            }

            MarriageCostService.ChargeResult chargeResult = marriageCostService.charge(onlineProposer, onlineTarget);
            if (!chargeResult.success()) {
                return;
            }

            MarriageRecord marriage = MarriageRecord.create(proposal.proposerId(), proposal.targetId(), Instant.now());
            taskDispatcher.runAsync(() -> {
                try {
                    repository.insert(marriage);
                    marriagesByPlayer.put(marriage.partnerOne(), marriage);
                    marriagesByPlayer.put(marriage.partnerTwo(), marriage);
                    taskDispatcher.runGlobal(() -> {
                        marriageCostService.notifyChargedPlayers(chargeResult);
                        notifyMarriageCreated(proposal);
                    });
                } catch (SQLException exception) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to save marriage", exception);
                    taskDispatcher.runGlobal(() -> {
                        marriageCostService.refund(chargeResult);
                        runOnlinePlayerTask(proposal.targetId(), onlinePlayer -> messages.send(onlinePlayer, "errors.storage-failure"));
                        runOnlinePlayerTask(proposal.proposerId(), onlinePlayer -> messages.send(onlinePlayer, "errors.storage-failure"));
                    });
                }
            });
        });
    }

    public void denyProposal(Player target, UUID proposerId) {
        PendingProposal proposal = proposalsByTarget.get(target.getUniqueId());
        if (proposal == null) {
            messages.send(target, "proposal.none");
            return;
        }
        if (proposerId != null && !proposal.proposerId().equals(proposerId)) {
            messages.send(target, "proposal.none-from-player");
            return;
        }

        clearPendingState(target.getUniqueId(), false);
        messages.send(target, "proposal.denied-target", Map.of("player", proposal.proposerName()));

        taskDispatcher.runGlobal(() -> runOnlinePlayerTask(
            proposal.proposerId(),
            proposer -> messages.send(
                proposer,
                "proposal.denied-proposer",
                Map.of("player", target.getName())
            )
        ));
    }

    public void divorce(Player player) {
        MarriageRecord marriage = getMarriage(player.getUniqueId());
        if (marriage == null) {
            messages.send(player, "marriage.not-married");
            return;
        }

        UUID partnerId = marriage.partnerOf(player.getUniqueId());
        taskDispatcher.runAsync(() -> {
            try {
                repository.delete(marriage);
                marriagesByPlayer.remove(marriage.partnerOne());
                marriagesByPlayer.remove(marriage.partnerTwo());
                taskDispatcher.runGlobal(() -> notifyDivorce(player.getUniqueId(), player.getName(), partnerId));
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "Failed to delete marriage", exception);
                taskDispatcher.runEntity(player, () -> messages.send(player, "errors.storage-failure"));
            }
        });
    }

    public void clearPendingState(UUID playerId, boolean disconnected) {
        PendingProposal incoming = proposalsByTarget.remove(playerId);
        if (incoming != null) {
            targetByProposer.remove(incoming.proposerId());
            cancelExpiryTask(playerId);

            if (disconnected) {
                taskDispatcher.runGlobal(() -> runOnlinePlayerTask(
                    incoming.proposerId(),
                    proposer -> messages.send(
                        proposer,
                        "proposal.cancelled-disconnect",
                        Map.of("player", incoming.targetName())
                    )
                ));
            }
        }

        UUID targetId = targetByProposer.remove(playerId);
        if (targetId == null) {
            return;
        }

        PendingProposal outgoing = proposalsByTarget.remove(targetId);
        cancelExpiryTask(targetId);
        if (!disconnected || outgoing == null) {
            return;
        }

        taskDispatcher.runGlobal(() -> runOnlinePlayerTask(
            targetId,
            target -> messages.send(
                target,
                "proposal.cancelled-disconnect",
                Map.of("player", outgoing.proposerName())
            )
        ));
    }

    private void expireProposal(UUID targetId, boolean notify) {
        PendingProposal proposal = proposalsByTarget.remove(targetId);
        if (proposal == null) {
            cancelExpiryTask(targetId);
            return;
        }

        targetByProposer.remove(proposal.proposerId());
        cancelExpiryTask(targetId);
        if (!notify) {
            return;
        }

        taskDispatcher.runGlobal(() -> {
            runOnlinePlayerTask(
                proposal.proposerId(),
                proposer -> messages.send(
                    proposer,
                    "proposal.expired-proposer",
                    Map.of("player", proposal.targetName())
                )
            );
            runOnlinePlayerTask(
                targetId,
                target -> messages.send(
                    target,
                    "proposal.expired-target",
                    Map.of("player", proposal.proposerName())
                )
            );
        });
    }

    private void cancelExpiryTask(UUID targetId) {
        ScheduledTask task = expiryTasks.remove(targetId);
        if (task != null) {
            task.cancel();
        }
    }

    private void notifyMarriageCreated(PendingProposal proposal) {
        runOnlinePlayerTask(
            proposal.targetId(),
            target -> messages.send(target, "marriage.created-self", Map.of("player", proposal.proposerName()))
        );
        runOnlinePlayerTask(
            proposal.proposerId(),
            proposer -> messages.send(proposer, "marriage.created-partner", Map.of("player", proposal.targetName()))
        );

        if (!settings.marriage().broadcastMarriages()) {
            return;
        }

        messages.broadcast(
            "marriage.broadcast",
            Map.of("player_one", proposal.proposerName(), "player_two", proposal.targetName())
        );
    }

    private void notifyDivorce(UUID playerId, String playerName, UUID partnerId) {
        OfflinePlayer partner = Bukkit.getOfflinePlayer(partnerId);
        runOnlinePlayerTask(
            playerId,
            player -> messages.send(
                player,
                "marriage.divorced-self",
                Map.of("player", nameOf(partner))
            )
        );
        runOnlinePlayerTask(
            partnerId,
            onlinePartner -> messages.send(
                onlinePartner,
                "marriage.divorced-partner",
                Map.of("player", playerName)
            )
        );
    }

    private void runOnlinePlayerTask(UUID playerId, Consumer<Player> action) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }

        taskDispatcher.runEntity(player, () -> action.accept(player));
    }

    private String nameOf(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }

    private Map<String, String> proposalPlaceholders(String playerName) {
        return Map.of(
            "player", playerName,
            "seconds", String.valueOf(settings.marriage().proposalExpirySeconds()),
            "cost", marriageCostService.displayAmount()
        );
    }
}
