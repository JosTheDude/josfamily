package gg.jos.josfamily.service;

import gg.jos.josfamily.JosFamily;
import gg.jos.josfamily.config.MessageService;
import gg.jos.josfamily.config.PluginSettings;
import gg.jos.josfamily.model.AdoptionRecord;
import gg.jos.josfamily.model.PendingAdoptionRequest;
import gg.jos.josfamily.scheduler.FoliaTaskDispatcher;
import gg.jos.josfamily.storage.SqlAdoptionRepository;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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

public final class AdoptionService {
    private static final Comparator<AdoptionRecord> ADOPTED_AT_COMPARATOR = Comparator.comparing(AdoptionRecord::adoptedAt);

    private final JosFamily plugin;
    private final FoliaTaskDispatcher taskDispatcher;
    private final PluginSettings settings;
    private final MessageService messages;
    private final SqlAdoptionRepository repository;
    private final ConcurrentMap<UUID, Set<AdoptionRecord>> adoptionsByParent = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Set<AdoptionRecord>> adoptionsByChild = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, PendingAdoptionRequest> requestsByChild = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, UUID> childByParent = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ScheduledTask> expiryTasks = new ConcurrentHashMap<>();

    public AdoptionService(
        JosFamily plugin,
        FoliaTaskDispatcher taskDispatcher,
        PluginSettings settings,
        MessageService messages,
        SqlAdoptionRepository repository
    ) {
        this.plugin = plugin;
        this.taskDispatcher = taskDispatcher;
        this.settings = settings;
        this.messages = messages;
        this.repository = repository;
    }

    public void initialize() throws SQLException {
        adoptionsByParent.clear();
        adoptionsByChild.clear();
        for (AdoptionRecord adoption : repository.loadAll()) {
            indexAdoption(adoption);
        }
    }

    public void shutdown() {
        requestsByChild.clear();
        childByParent.clear();
        expiryTasks.values().forEach(ScheduledTask::cancel);
        expiryTasks.clear();
    }

    public List<AdoptionRecord> parentsOf(UUID childId) {
        return sortedSnapshot(adoptionsByChild.get(childId));
    }

    public List<AdoptionRecord> childrenOf(UUID parentId) {
        return sortedSnapshot(adoptionsByParent.get(parentId));
    }

    public Optional<PendingAdoptionRequest> getIncomingRequest(UUID childId) {
        return Optional.ofNullable(requestsByChild.get(childId));
    }

    public PendingAdoptionRequest createRequest(Player parent, Player child) {
        if (hasAdoption(parent.getUniqueId(), child.getUniqueId())) {
            messages.send(parent, "adoption.already-related", Map.of("player", child.getName()));
            return null;
        }
        if (!settings.adoption().hasParentCapacity(parentsOf(child.getUniqueId()).size())) {
            messages.send(parent, "adoption.child-limit", Map.of("player", child.getName()));
            return null;
        }
        if (!settings.adoption().hasChildCapacity(childrenOf(parent.getUniqueId()).size())) {
            messages.send(parent, "adoption.parent-limit");
            return null;
        }
        if (childByParent.containsKey(parent.getUniqueId())) {
            messages.send(parent, "adoption-request.already-sent");
            return null;
        }
        if (requestsByChild.containsKey(child.getUniqueId())) {
            messages.send(parent, "adoption-request.target-busy", Map.of("player", child.getName()));
            return null;
        }

        Instant now = Instant.now();
        PendingAdoptionRequest request = new PendingAdoptionRequest(
            parent.getUniqueId(),
            parent.getName(),
            child.getUniqueId(),
            child.getName(),
            now,
            now.plusSeconds(settings.adoption().requestExpirySeconds())
        );

        requestsByChild.put(child.getUniqueId(), request);
        childByParent.put(parent.getUniqueId(), child.getUniqueId());

        ScheduledTask expiryTask = taskDispatcher.runAsyncLater(
            settings.adoption().requestExpirySeconds(),
            TimeUnit.SECONDS,
            () -> expireRequest(child.getUniqueId(), true)
        );
        expiryTasks.put(child.getUniqueId(), expiryTask);

        messages.send(parent, "adoption-request.sent", requestPlaceholders(child.getName()));
        messages.send(child, "adoption-request.received", requestPlaceholders(parent.getName()));
        return request;
    }

    public void acceptRequest(Player child, UUID parentId) {
        PendingAdoptionRequest request = requestsByChild.get(child.getUniqueId());
        if (request == null) {
            messages.send(child, "adoption-request.none");
            return;
        }
        if (parentId != null && !request.parentId().equals(parentId)) {
            messages.send(child, "adoption-request.none-from-player");
            return;
        }

        Player parent = Bukkit.getPlayer(request.parentId());
        if (parent == null || !parent.isOnline()) {
            clearPendingState(child.getUniqueId(), false);
            messages.send(child, "adoption-request.parent-offline");
            return;
        }
        if (hasAdoption(request.parentId(), child.getUniqueId())) {
            clearPendingState(child.getUniqueId(), false);
            messages.send(child, "adoption.already-related", Map.of("player", parent.getName()));
            return;
        }
        if (!settings.adoption().hasParentCapacity(parentsOf(child.getUniqueId()).size())) {
            clearPendingState(child.getUniqueId(), false);
            messages.send(child, "adoption.child-limit", Map.of("player", child.getName()));
            return;
        }
        if (!settings.adoption().hasChildCapacity(childrenOf(request.parentId()).size())) {
            clearPendingState(child.getUniqueId(), false);
            messages.send(child, "adoption.parent-limit");
            return;
        }

        clearPendingState(child.getUniqueId(), false);
        AdoptionRecord adoption = new AdoptionRecord(request.parentId(), request.childId(), Instant.now());
        taskDispatcher.runAsync(() -> {
            try {
                repository.insert(adoption);
                indexAdoption(adoption);
                taskDispatcher.runGlobal(() -> notifyAdoptionCreated(request));
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save adoption", exception);
                taskDispatcher.runGlobal(() -> {
                    runOnlinePlayerTask(request.childId(), player -> messages.send(player, "errors.storage-failure"));
                    runOnlinePlayerTask(request.parentId(), player -> messages.send(player, "errors.storage-failure"));
                });
            }
        });
    }

    public void denyRequest(Player child, UUID parentId) {
        PendingAdoptionRequest request = requestsByChild.get(child.getUniqueId());
        if (request == null) {
            messages.send(child, "adoption-request.none");
            return;
        }
        if (parentId != null && !request.parentId().equals(parentId)) {
            messages.send(child, "adoption-request.none-from-player");
            return;
        }

        clearPendingState(child.getUniqueId(), false);
        messages.send(child, "adoption-request.denied-child", Map.of("player", request.parentName()));
        taskDispatcher.runGlobal(() -> runOnlinePlayerTask(
            request.parentId(),
            parent -> messages.send(parent, "adoption-request.denied-parent", Map.of("player", child.getName()))
        ));
    }

    public void removeAdoption(Player actor, UUID otherPlayerId) {
        AdoptionRecord adoption = directAdoption(actor.getUniqueId(), otherPlayerId);
        if (adoption == null) {
            messages.send(actor, "adoption.not-related");
            return;
        }

        OfflinePlayer otherPlayer = Bukkit.getOfflinePlayer(otherPlayerId);
        taskDispatcher.runAsync(() -> {
            try {
                repository.delete(adoption.parentId(), adoption.childId());
                deindexAdoption(adoption);
                taskDispatcher.runGlobal(() -> notifyAdoptionRemoved(actor.getUniqueId(), actor.getName(), otherPlayerId, nameOf(otherPlayer)));
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "Failed to delete adoption", exception);
                taskDispatcher.runEntity(actor, () -> messages.send(actor, "errors.storage-failure"));
            }
        });
    }

    public void clearPendingState(UUID playerId, boolean disconnected) {
        PendingAdoptionRequest incoming = requestsByChild.remove(playerId);
        if (incoming != null) {
            childByParent.remove(incoming.parentId());
            cancelExpiryTask(playerId);

            if (disconnected) {
                taskDispatcher.runGlobal(() -> runOnlinePlayerTask(
                    incoming.parentId(),
                    parent -> messages.send(
                        parent,
                        "adoption-request.cancelled-disconnect",
                        Map.of("player", incoming.childName())
                    )
                ));
            }
        }

        UUID childId = childByParent.remove(playerId);
        if (childId == null) {
            return;
        }

        PendingAdoptionRequest outgoing = requestsByChild.remove(childId);
        cancelExpiryTask(childId);
        if (!disconnected || outgoing == null) {
            return;
        }

        taskDispatcher.runGlobal(() -> runOnlinePlayerTask(
            childId,
            child -> messages.send(
                child,
                "adoption-request.cancelled-disconnect",
                Map.of("player", outgoing.parentName())
            )
        ));
    }

    public boolean hasAdoption(UUID parentId, UUID childId) {
        return parentsOf(childId).stream().anyMatch(adoption -> adoption.parentId().equals(parentId));
    }

    private AdoptionRecord directAdoption(UUID first, UUID second) {
        for (AdoptionRecord adoption : parentsOf(first)) {
            if (adoption.parentId().equals(second)) {
                return adoption;
            }
        }

        for (AdoptionRecord adoption : childrenOf(first)) {
            if (adoption.childId().equals(second)) {
                return adoption;
            }
        }

        return null;
    }

    private void expireRequest(UUID childId, boolean notify) {
        PendingAdoptionRequest request = requestsByChild.remove(childId);
        if (request == null) {
            cancelExpiryTask(childId);
            return;
        }

        childByParent.remove(request.parentId());
        cancelExpiryTask(childId);
        if (!notify) {
            return;
        }

        taskDispatcher.runGlobal(() -> {
            runOnlinePlayerTask(
                request.parentId(),
                parent -> messages.send(
                    parent,
                    "adoption-request.expired-parent",
                    Map.of("player", request.childName())
                )
            );
            runOnlinePlayerTask(
                childId,
                child -> messages.send(
                    child,
                    "adoption-request.expired-child",
                    Map.of("player", request.parentName())
                )
            );
        });
    }

    private void cancelExpiryTask(UUID childId) {
        ScheduledTask task = expiryTasks.remove(childId);
        if (task != null) {
            task.cancel();
        }
    }

    private void indexAdoption(AdoptionRecord adoption) {
        adoptionsByParent.compute(adoption.parentId(), (ignored, existing) -> withAdded(existing, adoption));
        adoptionsByChild.compute(adoption.childId(), (ignored, existing) -> withAdded(existing, adoption));
    }

    private void deindexAdoption(AdoptionRecord adoption) {
        adoptionsByParent.computeIfPresent(adoption.parentId(), (ignored, existing) -> withRemoved(existing, adoption));
        adoptionsByChild.computeIfPresent(adoption.childId(), (ignored, existing) -> withRemoved(existing, adoption));
    }

    private Set<AdoptionRecord> withAdded(Set<AdoptionRecord> existing, AdoptionRecord adoption) {
        Set<AdoptionRecord> updated = existing == null ? ConcurrentHashMap.newKeySet() : ConcurrentHashMap.newKeySet(existing.size() + 1);
        if (existing != null) {
            updated.addAll(existing);
        }
        updated.add(adoption);
        return Set.copyOf(updated);
    }

    private Set<AdoptionRecord> withRemoved(Set<AdoptionRecord> existing, AdoptionRecord adoption) {
        if (existing == null || existing.isEmpty()) {
            return null;
        }

        Set<AdoptionRecord> updated = ConcurrentHashMap.newKeySet(existing.size());
        updated.addAll(existing);
        updated.remove(adoption);
        return updated.isEmpty() ? null : Set.copyOf(updated);
    }

    private List<AdoptionRecord> sortedSnapshot(Set<AdoptionRecord> adoptions) {
        if (adoptions == null || adoptions.isEmpty()) {
            return List.of();
        }

        List<AdoptionRecord> snapshot = new ArrayList<>(adoptions);
        snapshot.sort(ADOPTED_AT_COMPARATOR);
        return List.copyOf(snapshot);
    }

    private void notifyAdoptionCreated(PendingAdoptionRequest request) {
        runOnlinePlayerTask(
            request.childId(),
            child -> messages.send(child, "adoption.created-child", Map.of("player", request.parentName()))
        );
        runOnlinePlayerTask(
            request.parentId(),
            parent -> messages.send(parent, "adoption.created-parent", Map.of("player", request.childName()))
        );

        if (!settings.adoption().broadcastAdoptions()) {
            return;
        }

        messages.broadcast(
            "adoption.broadcast",
            Map.of("parent", request.parentName(), "child", request.childName())
        );
    }

    private void notifyAdoptionRemoved(UUID actorId, String actorName, UUID otherPlayerId, String otherPlayerName) {
        runOnlinePlayerTask(
            actorId,
            actor -> messages.send(actor, "adoption.removed-self", Map.of("player", otherPlayerName))
        );
        runOnlinePlayerTask(
            otherPlayerId,
            otherPlayer -> messages.send(otherPlayer, "adoption.removed-other", Map.of("player", actorName))
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

    private Map<String, String> requestPlaceholders(String playerName) {
        return Map.of(
            "player", playerName,
            "seconds", String.valueOf(settings.adoption().requestExpirySeconds())
        );
    }
}
