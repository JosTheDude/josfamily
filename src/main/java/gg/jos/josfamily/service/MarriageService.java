package gg.jos.josfamily.service;

import gg.jos.josfamily.JosFamily;
import gg.jos.josfamily.compat.economy.MarriageCostService;
import gg.jos.josfamily.config.MessageService;
import gg.jos.josfamily.config.PluginSettings;
import gg.jos.josfamily.model.ChargeReceipt;
import gg.jos.josfamily.model.MarriageRecord;
import gg.jos.josfamily.model.PendingProposal;
import gg.jos.josfamily.scheduler.FoliaTaskDispatcher;
import gg.jos.josfamily.storage.SqlMarriageRepository;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final Object persistenceMonitor = new Object();
    private final Object operationMonitor = new Object();
    private final Set<UUID> playersWithActiveOperation = new LinkedHashSet<>();
    private int inFlightPersistenceTasks;
    private boolean shuttingDown;

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
        synchronized (persistenceMonitor) {
            shuttingDown = false;
        }
        marriagesByPlayer.clear();
        for (MarriageRecord marriage : repository.loadAll()) {
            marriagesByPlayer.put(marriage.partnerOne(), marriage);
            marriagesByPlayer.put(marriage.partnerTwo(), marriage);
        }
    }

    public void shutdown() {
        synchronized (persistenceMonitor) {
            shuttingDown = true;
        }
        proposalsByTarget.clear();
        targetByProposer.clear();
        expiryTasks.values().forEach(ScheduledTask::cancel);
        expiryTasks.clear();
        waitForPersistenceTasks();
    }

    public MarriageRecord getMarriage(UUID playerId) {
        return marriagesByPlayer.get(playerId);
    }

    public Optional<PendingProposal> getIncomingProposal(UUID targetId) {
        return Optional.ofNullable(proposalsByTarget.get(targetId));
    }

    public boolean hasPendingProposal(UUID playerId) {
        return proposalsByTarget.containsKey(playerId) || targetByProposer.containsKey(playerId);
    }

    public DirectMarriageEligibility checkDirectMarriageEligibility(UUID proposerId, UUID targetId) {
        if (proposerId.equals(targetId)) {
            return DirectMarriageEligibility.SELF;
        }
        if (getMarriage(proposerId) != null) {
            return DirectMarriageEligibility.PROPOSER_MARRIED;
        }
        if (getMarriage(targetId) != null) {
            return DirectMarriageEligibility.TARGET_MARRIED;
        }
        if (hasPendingProposal(proposerId)) {
            return DirectMarriageEligibility.PROPOSER_HAS_PENDING_PROPOSAL;
        }
        if (hasPendingProposal(targetId)) {
            return DirectMarriageEligibility.TARGET_HAS_PENDING_PROPOSAL;
        }
        return DirectMarriageEligibility.ELIGIBLE;
    }

    public PendingProposal createProposal(Player proposer, UUID targetId, String targetName) {
        if (isShuttingDown()) {
            messages.send(proposer, "errors.storage-failure");
            return null;
        }
        if (getMarriage(proposer.getUniqueId()) != null) {
            messages.send(proposer, "marriage.already-married");
            return null;
        }
        if (getMarriage(targetId) != null) {
            messages.send(
                proposer,
                "marriage.target-married",
                Map.of("player", targetName)
            );
            return null;
        }
        if (targetByProposer.containsKey(proposer.getUniqueId())) {
            messages.send(proposer, "proposal.already-sent");
            return null;
        }
        if (proposalsByTarget.containsKey(targetId)) {
            messages.send(
                proposer,
                "proposal.target-busy",
                Map.of("player", targetName)
            );
            return null;
        }

        MarriageCostService.ChargeResult sendChargeResult = marriageCostService.chargeProposalSend(
            proposer.getUniqueId(),
            proposer.getName(),
            targetId,
            targetName
        );
        if (!sendChargeResult.success()) {
            return null;
        }

        Instant now = Instant.now();
        PendingProposal proposal = new PendingProposal(
            proposer.getUniqueId(),
            proposer.getName(),
            targetId,
            targetName,
            now,
            now.plusSeconds(settings.marriage().proposalExpirySeconds()),
            sendChargeResult.receipts()
        );

        proposalsByTarget.put(targetId, proposal);
        targetByProposer.put(proposer.getUniqueId(), targetId);

        ScheduledTask expiryTask = taskDispatcher.runAsyncLater(
            settings.marriage().proposalExpirySeconds(),
            TimeUnit.SECONDS,
            () -> expireProposal(targetId, true)
        );
        expiryTasks.put(targetId, expiryTask);

        messages.send(
            proposer,
            "proposal.sent",
            proposalPlaceholders(targetName)
        );
        runOnlinePlayerTask(
            targetId,
            onlineTarget -> messages.send(
                onlineTarget,
                "proposal.received",
                proposalPlaceholders(proposer.getName())
            )
        );
        marriageCostService.notifyChargedPlayers(sendChargeResult);
        return proposal;
    }

    public boolean tryAcceptReciprocalProposal(Player proposer, UUID targetId) {
        PendingProposal incoming = proposalsByTarget.get(proposer.getUniqueId());
        if (incoming == null || !incoming.proposerId().equals(targetId)) {
            return false;
        }

        acceptProposal(proposer, incoming.proposerName());
        return true;
    }

    public void acceptProposal(Player target, String proposerName) {
        if (isShuttingDown()) {
            messages.send(target, "errors.storage-failure");
            return;
        }
        PendingProposal proposal = proposalsByTarget.get(target.getUniqueId());
        if (proposal == null) {
            messages.send(target, "proposal.none");
            return;
        }
        if (proposerName != null && !proposal.proposerName().equalsIgnoreCase(proposerName)) {
            messages.send(target, "proposal.none-from-player");
            return;
        }

        if (getMarriage(target.getUniqueId()) != null || getMarriage(proposal.proposerId()) != null) {
            clearPendingState(target.getUniqueId(), false);
            messages.send(target, "marriage.already-married");
            return;
        }
        if (!beginPlayerOperation(proposal.proposerId(), proposal.targetId())) {
            messages.send(target, "errors.operation-in-progress");
            return;
        }

        clearPendingState(target.getUniqueId(), false);
        taskDispatcher.runGlobal(() -> {
            Player onlineTarget = Bukkit.getPlayer(proposal.targetId());
            Player onlineProposer = Bukkit.getPlayer(proposal.proposerId());
            if (onlineTarget == null || onlineProposer == null) {
                endPlayerOperation(proposal.proposerId(), proposal.targetId());
                runOnlinePlayerTask(proposal.targetId(), player -> messages.send(player, "proposal.proposer-offline"));
                return;
            }

            MarriageCostService.ChargeResult chargeResult = marriageCostService.chargeProposalAcceptance(
                proposal.proposerId(),
                proposal.proposerName(),
                proposal.targetId(),
                proposal.targetName()
            );
            if (!chargeResult.success()) {
                endPlayerOperation(proposal.proposerId(), proposal.targetId());
                return;
            }
            if (!beginPersistenceTask()) {
                endPlayerOperation(proposal.proposerId(), proposal.targetId());
                marriageCostService.refund(combineReceipts(proposal.sendChargeReceipts(), chargeResult.receipts()));
                runOnlinePlayerTask(proposal.targetId(), onlinePlayer -> messages.send(onlinePlayer, "errors.storage-failure"));
                runOnlinePlayerTask(proposal.proposerId(), onlinePlayer -> messages.send(onlinePlayer, "errors.storage-failure"));
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
                        marriageCostService.refund(combineReceipts(proposal.sendChargeReceipts(), chargeResult.receipts()));
                        runOnlinePlayerTask(proposal.targetId(), onlinePlayer -> messages.send(onlinePlayer, "errors.storage-failure"));
                        runOnlinePlayerTask(proposal.proposerId(), onlinePlayer -> messages.send(onlinePlayer, "errors.storage-failure"));
                    });
                } finally {
                    endPlayerOperation(proposal.proposerId(), proposal.targetId());
                    endPersistenceTask();
                }
            });
        });
    }

    public void denyProposal(Player target, String proposerName) {
        PendingProposal proposal = proposalsByTarget.get(target.getUniqueId());
        if (proposal == null) {
            messages.send(target, "proposal.none");
            return;
        }
        if (proposerName != null && !proposal.proposerName().equalsIgnoreCase(proposerName)) {
            messages.send(target, "proposal.none-from-player");
            return;
        }

        clearPendingState(target.getUniqueId(), false);
        messages.send(target, "proposal.denied-target", Map.of("player", proposal.proposerName()));

        runOnlinePlayerTask(
            proposal.proposerId(),
            proposer -> messages.send(
                proposer,
                "proposal.denied-proposer",
                Map.of("player", target.getName())
            )
        );
    }

    public void divorce(Player player) {
        if (isShuttingDown()) {
            messages.send(player, "errors.storage-failure");
            return;
        }
        MarriageRecord marriage = getMarriage(player.getUniqueId());
        if (marriage == null) {
            messages.send(player, "marriage.not-married");
            return;
        }

        UUID partnerId = marriage.partnerOf(player.getUniqueId());
        if (!beginPlayerOperation(player.getUniqueId(), partnerId)) {
            messages.send(player, "errors.operation-in-progress");
            return;
        }
        if (!beginPersistenceTask()) {
            endPlayerOperation(player.getUniqueId(), partnerId);
            messages.send(player, "errors.storage-failure");
            return;
        }
        taskDispatcher.runAsync(() -> {
            try {
                repository.delete(marriage);
                marriagesByPlayer.remove(marriage.partnerOne());
                marriagesByPlayer.remove(marriage.partnerTwo());
                taskDispatcher.runGlobal(() -> notifyDivorce(player.getUniqueId(), player.getName(), partnerId));
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "Failed to delete marriage", exception);
                taskDispatcher.runEntity(player, () -> messages.send(player, "errors.storage-failure"));
            } finally {
                endPlayerOperation(player.getUniqueId(), partnerId);
                endPersistenceTask();
            }
        });
    }

    public void createDirectMarriage(DirectMarriageRequest request, Consumer<DirectMarriageCreationResult> completion) {
        if (isShuttingDown()) {
            completion.accept(DirectMarriageCreationResult.STORAGE_FAILURE);
            return;
        }

        DirectMarriageEligibility eligibility = checkDirectMarriageEligibility(request.proposerId(), request.targetId());
        if (eligibility != DirectMarriageEligibility.ELIGIBLE) {
            completion.accept(DirectMarriageCreationResult.fromEligibility(eligibility));
            return;
        }
        if (!beginPlayerOperation(request.proposerId(), request.targetId())) {
            completion.accept(DirectMarriageCreationResult.OPERATION_IN_PROGRESS);
            return;
        }
        if (!beginPersistenceTask()) {
            endPlayerOperation(request.proposerId(), request.targetId());
            completion.accept(DirectMarriageCreationResult.STORAGE_FAILURE);
            return;
        }

        MarriageRecord marriage = MarriageRecord.create(request.proposerId(), request.targetId(), Instant.now());
        taskDispatcher.runAsync(() -> {
            try {
                repository.insert(marriage);
                marriagesByPlayer.put(marriage.partnerOne(), marriage);
                marriagesByPlayer.put(marriage.partnerTwo(), marriage);
                taskDispatcher.runGlobal(() -> {
                    notifyMarriageCreated(request.proposerId(), request.proposerName(), request.targetId(), request.targetName());
                    completion.accept(DirectMarriageCreationResult.SUCCESS);
                });
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save marriage", exception);
                taskDispatcher.runGlobal(() -> completion.accept(DirectMarriageCreationResult.STORAGE_FAILURE));
            } finally {
                endPlayerOperation(request.proposerId(), request.targetId());
                endPersistenceTask();
            }
        });
    }

    public void clearPendingState(UUID playerId, boolean disconnected) {
        PendingProposal incoming = proposalsByTarget.remove(playerId);
        if (incoming != null) {
            targetByProposer.remove(incoming.proposerId());
            cancelExpiryTask(playerId);

            if (disconnected) {
                runOnlinePlayerTask(
                    incoming.proposerId(),
                    proposer -> messages.send(
                        proposer,
                        "proposal.cancelled-disconnect",
                        Map.of("player", incoming.targetName())
                    )
                );
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

        runOnlinePlayerTask(
            targetId,
            target -> messages.send(
                target,
                "proposal.cancelled-disconnect",
                Map.of("player", outgoing.proposerName())
            )
        );
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
    }

    private void cancelExpiryTask(UUID targetId) {
        ScheduledTask task = expiryTasks.remove(targetId);
        if (task != null) {
            task.cancel();
        }
    }

    private boolean isShuttingDown() {
        synchronized (persistenceMonitor) {
            return shuttingDown;
        }
    }

    private boolean beginPlayerOperation(UUID... playerIds) {
        List<UUID> uniquePlayerIds = uniquePlayerIds(playerIds);
        synchronized (operationMonitor) {
            for (UUID playerId : uniquePlayerIds) {
                if (playersWithActiveOperation.contains(playerId)) {
                    return false;
                }
            }
            playersWithActiveOperation.addAll(uniquePlayerIds);
            return true;
        }
    }

    private void endPlayerOperation(UUID... playerIds) {
        List<UUID> uniquePlayerIds = uniquePlayerIds(playerIds);
        synchronized (operationMonitor) {
            playersWithActiveOperation.removeAll(uniquePlayerIds);
        }
    }

    private boolean beginPersistenceTask() {
        synchronized (persistenceMonitor) {
            if (shuttingDown) {
                return false;
            }

            inFlightPersistenceTasks++;
            return true;
        }
    }

    private void endPersistenceTask() {
        synchronized (persistenceMonitor) {
            inFlightPersistenceTasks--;
            if (inFlightPersistenceTasks == 0) {
                persistenceMonitor.notifyAll();
            }
        }
    }

    private void waitForPersistenceTasks() {
        synchronized (persistenceMonitor) {
            while (inFlightPersistenceTasks > 0) {
                try {
                    persistenceMonitor.wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    plugin.getLogger().log(Level.WARNING, "Interrupted while waiting for persistence tasks to finish", exception);
                    return;
                }
            }
        }
    }

    private void notifyMarriageCreated(PendingProposal proposal) {
        notifyMarriageCreated(proposal.proposerId(), proposal.proposerName(), proposal.targetId(), proposal.targetName());
    }

    private void notifyMarriageCreated(UUID proposerId, String proposerName, UUID targetId, String targetName) {
        runOnlinePlayerTask(
            targetId,
            target -> messages.send(target, "marriage.created-self", Map.of("player", proposerName))
        );
        runOnlinePlayerTask(
            proposerId,
            proposer -> messages.send(proposer, "marriage.created-partner", Map.of("player", targetName))
        );

        if (!settings.marriage().broadcastMarriages()) {
            return;
        }

        messages.broadcast(
            "marriage.broadcast",
            Map.of("player_one", proposerName, "player_two", targetName)
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
        taskDispatcher.runPlayer(playerId, action);
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

    private List<ChargeReceipt> combineReceipts(List<ChargeReceipt> first, List<ChargeReceipt> second) {
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }

        List<ChargeReceipt> combined = new java.util.ArrayList<>(first.size() + second.size());
        combined.addAll(first);
        combined.addAll(second);
        return combined;
    }

    private List<UUID> uniquePlayerIds(UUID... playerIds) {
        LinkedHashSet<UUID> unique = new LinkedHashSet<>();
        for (UUID playerId : playerIds) {
            if (playerId != null) {
                unique.add(playerId);
            }
        }
        return List.copyOf(unique);
    }

    public record DirectMarriageRequest(
        UUID proposerId,
        String proposerName,
        UUID targetId,
        String targetName
    ) {}

    public enum DirectMarriageEligibility {
        ELIGIBLE,
        SELF,
        PROPOSER_MARRIED,
        TARGET_MARRIED,
        PROPOSER_HAS_PENDING_PROPOSAL,
        TARGET_HAS_PENDING_PROPOSAL
    }

    public enum DirectMarriageCreationResult {
        SUCCESS,
        STORAGE_FAILURE,
        OPERATION_IN_PROGRESS,
        PROPOSER_MARRIED,
        TARGET_MARRIED,
        PROPOSER_HAS_PENDING_PROPOSAL,
        TARGET_HAS_PENDING_PROPOSAL,
        SELF;

        private static DirectMarriageCreationResult fromEligibility(DirectMarriageEligibility eligibility) {
            return switch (eligibility) {
                case ELIGIBLE -> SUCCESS;
                case SELF -> SELF;
                case PROPOSER_MARRIED -> PROPOSER_MARRIED;
                case TARGET_MARRIED -> TARGET_MARRIED;
                case PROPOSER_HAS_PENDING_PROPOSAL -> PROPOSER_HAS_PENDING_PROPOSAL;
                case TARGET_HAS_PENDING_PROPOSAL -> TARGET_HAS_PENDING_PROPOSAL;
            };
        }
    }
}
