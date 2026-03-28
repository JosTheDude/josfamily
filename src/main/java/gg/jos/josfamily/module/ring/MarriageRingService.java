package gg.jos.josfamily.module.ring;

import gg.jos.josfamily.JosFamily;
import gg.jos.josfamily.compat.economy.VaultEconomyHook;
import gg.jos.josfamily.config.MessageService;
import gg.jos.josfamily.config.PluginSettings.MarriageRingSettings;
import gg.jos.josfamily.service.MarriageService;
import gg.jos.josfamily.service.MarriageService.DirectMarriageCreationResult;
import gg.jos.josfamily.service.MarriageService.DirectMarriageEligibility;
import gg.jos.josfamily.service.MarriageService.DirectMarriageRequest;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public final class MarriageRingService {
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#,##0.00");
    private static final String RING_MARKER = "marriage_ring";

    private final JosFamily plugin;
    private final MarriageRingSettings settings;
    private final MessageService messages;
    private final MarriageService marriageService;
    private final VaultEconomyHook economyHook;
    private final NamespacedKey ringTypeKey;
    private final NamespacedKey ringIdKey;
    private final NamespacedKey proposerIdKey;
    private final NamespacedKey proposerNameKey;
    private final NamespacedKey targetIdKey;
    private final NamespacedKey targetNameKey;
    private final Set<UUID> activationsInProgress = ConcurrentHashMap.newKeySet();

    private MarriageRingService(
        JosFamily plugin,
        MarriageRingSettings settings,
        MessageService messages,
        MarriageService marriageService,
        VaultEconomyHook economyHook
    ) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.marriageService = marriageService;
        this.economyHook = economyHook;
        this.ringTypeKey = new NamespacedKey(plugin, "marriage_ring_type");
        this.ringIdKey = new NamespacedKey(plugin, "marriage_ring_id");
        this.proposerIdKey = new NamespacedKey(plugin, "marriage_ring_proposer_id");
        this.proposerNameKey = new NamespacedKey(plugin, "marriage_ring_proposer_name");
        this.targetIdKey = new NamespacedKey(plugin, "marriage_ring_target_id");
        this.targetNameKey = new NamespacedKey(plugin, "marriage_ring_target_name");
    }

    public static MarriageRingService create(
        JosFamily plugin,
        MarriageRingSettings settings,
        MessageService messages,
        MarriageService marriageService
    ) {
        VaultEconomyHook hook = settings.enabled() && settings.requiresEconomy() ? VaultEconomyHook.find() : null;
        if (settings.enabled() && settings.requiresEconomy() && hook == null) {
            plugin.getLogger().log(Level.WARNING, "Marriage ring module is enabled but Vault economy was not found.");
        }

        return new MarriageRingService(plugin, settings, messages, marriageService, hook);
    }

    public boolean isMarriageRing(ItemStack itemStack) {
        return readRingData(itemStack).isPresent();
    }

    public void purchaseRing(Player proposer, UUID targetId, String targetName) {
        if (!settings.enabled()) {
            messages.send(proposer, "ring.disabled");
            return;
        }

        DirectMarriageEligibility eligibility = marriageService.checkDirectMarriageEligibility(proposer.getUniqueId(), targetId);
        if (!handleEligibilityFailure(proposer, targetName, eligibility)) {
            return;
        }
        if (!chargePurchase(proposer, targetName)) {
            return;
        }

        ItemStack ring = createRingItem(proposer.getUniqueId(), proposer.getName(), targetId, targetName);
        Map<Integer, ItemStack> leftovers = proposer.getInventory().addItem(ring);
        for (ItemStack leftover : leftovers.values()) {
            proposer.getWorld().dropItem(proposer.getLocation(), leftover);
        }

        messages.send(
            proposer,
            "ring.purchased",
            Map.of("player", targetName, "cost", displayCost())
        );
        if (!leftovers.isEmpty()) {
            messages.send(proposer, "ring.inventory-full", Map.of("player", targetName));
        }
    }

    public void activateRing(Player recipient, ItemStack itemStack) {
        Optional<MarriageRingData> optionalData = readRingData(itemStack);
        if (optionalData.isEmpty()) {
            return;
        }

        MarriageRingData ringData = optionalData.get();
        if (!settings.enabled()) {
            messages.send(recipient, "ring.disabled");
            return;
        }
        if (!ringData.targetId().equals(recipient.getUniqueId())) {
            messages.send(recipient, "ring.not-your-ring", Map.of("player", ringData.targetName()));
            return;
        }
        if (!activationsInProgress.add(ringData.ringId())) {
            messages.send(recipient, "ring.activation-pending");
            return;
        }

        DirectMarriageEligibility eligibility = marriageService.checkDirectMarriageEligibility(ringData.proposerId(), ringData.targetId());
        if (!handleActivationEligibilityFailure(recipient, ringData, eligibility)) {
            activationsInProgress.remove(ringData.ringId());
            return;
        }

        String recipientName = recipient.getName();
        plugin.taskDispatcher().runGlobal(() -> {
            if (Bukkit.getPlayer(ringData.proposerId()) == null) {
                plugin.taskDispatcher().runEntity(recipient, () -> {
                    activationsInProgress.remove(ringData.ringId());
                    messages.send(recipient, "ring.proposer-offline", Map.of("player", ringData.proposerName()));
                });
                return;
            }

            marriageService.createDirectMarriage(
                new DirectMarriageRequest(ringData.proposerId(), ringData.proposerName(), ringData.targetId(), recipientName),
                result -> plugin.taskDispatcher().runEntity(recipient, () -> finishActivation(recipient, ringData, result))
            );
        });
    }

    private void finishActivation(Player recipient, MarriageRingData ringData, DirectMarriageCreationResult result) {
        try {
            switch (result) {
                case SUCCESS -> {
                    removeRing(recipient, ringData.ringId());
                    messages.send(recipient, "ring.used", Map.of("player", ringData.proposerName()));
                }
                case STORAGE_FAILURE -> messages.send(recipient, "errors.storage-failure");
                case OPERATION_IN_PROGRESS -> messages.send(recipient, "errors.operation-in-progress");
                case PROPOSER_MARRIED -> messages.send(recipient, "ring.proposer-married", Map.of("player", ringData.proposerName()));
                case TARGET_MARRIED -> messages.send(recipient, "marriage.already-married");
                case PROPOSER_HAS_PENDING_PROPOSAL -> messages.send(recipient, "ring.proposer-has-pending-proposal", Map.of("player", ringData.proposerName()));
                case TARGET_HAS_PENDING_PROPOSAL -> messages.send(
                    recipient,
                    "ring.target-has-pending-proposal",
                    Map.of("player", ringData.targetName())
                );
                case SELF -> messages.send(recipient, "marriage.cannot-marry-self");
            }
        } finally {
            activationsInProgress.remove(ringData.ringId());
        }
    }

    private boolean chargePurchase(Player proposer, String targetName) {
        if (!settings.requiresEconomy()) {
            return true;
        }
        if (economyHook == null) {
            messages.send(proposer, "errors.economy-unavailable");
            return false;
        }
        if (!economyHook.has(proposer, settings.cost())) {
            messages.send(
                proposer,
                "ring.insufficient-funds",
                Map.of("amount", displayCost(), "player", targetName)
            );
            return false;
        }
        if (!economyHook.withdraw(proposer, settings.cost())) {
            messages.send(proposer, "errors.economy-unavailable");
            return false;
        }
        return true;
    }

    private ItemStack createRingItem(UUID proposerId, String proposerName, UUID targetId, String targetName) {
        ItemStack itemStack = new ItemStack(settings.material());
        ItemMeta meta = itemStack.getItemMeta();
        Map<String, String> placeholders = Map.of("player", targetName, "proposer", proposerName);
        meta.displayName(messages.component("ring.item-name", placeholders));
        List<Component> lore = messages.componentList("ring.item-lore", placeholders);
        meta.lore(lore);
        if (settings.enchantedGlint()) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        PersistentDataContainer container = meta.getPersistentDataContainer();
        UUID ringId = UUID.randomUUID();
        container.set(ringTypeKey, PersistentDataType.STRING, RING_MARKER);
        container.set(ringIdKey, PersistentDataType.STRING, ringId.toString());
        container.set(proposerIdKey, PersistentDataType.STRING, proposerId.toString());
        container.set(proposerNameKey, PersistentDataType.STRING, proposerName);
        container.set(targetIdKey, PersistentDataType.STRING, targetId.toString());
        container.set(targetNameKey, PersistentDataType.STRING, targetName);
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    private Optional<MarriageRingData> readRingData(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir()) {
            return Optional.empty();
        }

        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }

        PersistentDataContainer container = meta.getPersistentDataContainer();
        String ringType = container.get(ringTypeKey, PersistentDataType.STRING);
        String ringId = container.get(ringIdKey, PersistentDataType.STRING);
        String proposerId = container.get(proposerIdKey, PersistentDataType.STRING);
        String proposerName = container.get(proposerNameKey, PersistentDataType.STRING);
        String targetId = container.get(targetIdKey, PersistentDataType.STRING);
        String targetName = container.get(targetNameKey, PersistentDataType.STRING);
        if (!RING_MARKER.equals(ringType)
            || ringId == null
            || proposerId == null
            || proposerName == null
            || targetId == null
            || targetName == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(new MarriageRingData(
                UUID.fromString(ringId),
                UUID.fromString(proposerId),
                proposerName,
                UUID.fromString(targetId),
                targetName
            ));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private boolean handleEligibilityFailure(Player player, String targetName, DirectMarriageEligibility eligibility) {
        return switch (eligibility) {
            case ELIGIBLE -> true;
            case SELF -> {
                messages.send(player, "marriage.cannot-marry-self");
                yield false;
            }
            case PROPOSER_MARRIED -> {
                messages.send(player, "marriage.already-married");
                yield false;
            }
            case TARGET_MARRIED -> {
                messages.send(player, "marriage.target-married", Map.of("player", targetName));
                yield false;
            }
            case PROPOSER_HAS_PENDING_PROPOSAL -> {
                messages.send(player, "ring.proposer-has-pending-proposal", Map.of("player", "You"));
                yield false;
            }
            case TARGET_HAS_PENDING_PROPOSAL -> {
                messages.send(player, "ring.target-has-pending-proposal", Map.of("player", targetName));
                yield false;
            }
        };
    }

    private boolean handleActivationEligibilityFailure(Player recipient, MarriageRingData ringData, DirectMarriageEligibility eligibility) {
        return switch (eligibility) {
            case ELIGIBLE -> true;
            case SELF -> {
                messages.send(recipient, "marriage.cannot-marry-self");
                yield false;
            }
            case PROPOSER_MARRIED -> {
                messages.send(recipient, "ring.proposer-married", Map.of("player", ringData.proposerName()));
                yield false;
            }
            case TARGET_MARRIED -> {
                messages.send(recipient, "marriage.already-married");
                yield false;
            }
            case PROPOSER_HAS_PENDING_PROPOSAL -> {
                messages.send(recipient, "ring.proposer-has-pending-proposal", Map.of("player", ringData.proposerName()));
                yield false;
            }
            case TARGET_HAS_PENDING_PROPOSAL -> {
                messages.send(recipient, "ring.target-has-pending-proposal", Map.of("player", ringData.targetName()));
                yield false;
            }
        };
    }

    private void removeRing(Player player, UUID ringId) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack itemStack = inventory.getItem(slot);
            if (!hasRingId(itemStack, ringId)) {
                continue;
            }
            if (itemStack.getAmount() <= 1) {
                inventory.setItem(slot, null);
            } else {
                itemStack.setAmount(itemStack.getAmount() - 1);
            }
            return;
        }
    }

    private boolean hasRingId(ItemStack itemStack, UUID ringId) {
        return readRingData(itemStack)
            .map(MarriageRingData::ringId)
            .filter(ringId::equals)
            .isPresent();
    }

    private String displayCost() {
        if (!settings.requiresEconomy()) {
            return messages.raw("format.free-cost", "free");
        }
        if (economyHook != null) {
            return economyHook.format(settings.cost());
        }
        return DECIMAL_FORMAT.format(settings.cost());
    }

    private record MarriageRingData(
        UUID ringId,
        UUID proposerId,
        String proposerName,
        UUID targetId,
        String targetName
    ) {}
}
