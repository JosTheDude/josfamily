package gg.jos.josfamily.compat.economy;

import gg.jos.josfamily.JosFamily;
import gg.jos.josfamily.config.MessageService;
import gg.jos.josfamily.config.PluginSettings.MarriageCostChargeStage;
import gg.jos.josfamily.config.PluginSettings.MarriageCostSettings;
import gg.jos.josfamily.model.ChargeReceipt;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.OfflinePlayer;

public final class MarriageCostService {
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#,##0.00");

    private final JosFamily plugin;
    private final MarriageCostSettings settings;
    private final MessageService messages;
    private final VaultEconomyHook economyHook;

    private MarriageCostService(
        JosFamily plugin,
        MarriageCostSettings settings,
        MessageService messages,
        VaultEconomyHook economyHook
    ) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.economyHook = economyHook;
    }

    public static MarriageCostService create(JosFamily plugin, MarriageCostSettings settings, MessageService messages) {
        VaultEconomyHook hook = settings.active() ? VaultEconomyHook.find() : null;
        if (settings.active() && hook == null) {
            plugin.getLogger().log(Level.WARNING, "Marriage cost module is enabled but Vault economy was not found.");
        }

        return new MarriageCostService(plugin, settings, messages, hook);
    }

    public String displayAmount() {
        if (!settings.active()) {
            return messages.raw("format.free-cost", "free");
        }

        return formatAmount(settings.amount());
    }

    public String displayAcceptanceCostForTarget() {
        return displayAmountFor(settings.chargesTargetAt(MarriageCostChargeStage.ACCEPT));
    }

    public ChargeResult chargeProposalSend(UUID proposerId, String proposerName, UUID targetId, String targetName) {
        return chargeForStage(MarriageCostChargeStage.SEND, proposerId, proposerName, targetId, targetName);
    }

    public ChargeResult chargeProposalAcceptance(UUID proposerId, String proposerName, UUID targetId, String targetName) {
        return chargeForStage(MarriageCostChargeStage.ACCEPT, proposerId, proposerName, targetId, targetName);
    }

    public void refund(List<ChargeReceipt> receipts) {
        refundReceipts(receipts, true);
    }

    private ChargeResult chargeForStage(
        MarriageCostChargeStage stage,
        UUID proposerId,
        String proposerName,
        UUID targetId,
        String targetName
    ) {
        if (!settings.active()) {
            return ChargeResult.free();
        }
        if (!settings.chargesProposerAt(stage) && !settings.chargesTargetAt(stage)) {
            return ChargeResult.free();
        }
        if (economyHook == null) {
            sendIfOnline(targetId, "errors.economy-unavailable", Map.of());
            sendIfOnline(proposerId, "errors.economy-unavailable", Map.of());
            return ChargeResult.failed();
        }

        List<ChargeReceipt> receipts = new ArrayList<>();
        if (settings.chargesProposerAt(stage) && !chargePlayer(proposerId, "cost.insufficient-self", targetName, receipts)) {
            return ChargeResult.failed();
        }
        if (settings.chargesTargetAt(stage) && !chargePlayer(targetId, "cost.insufficient-self", proposerName, receipts)) {
            if (settings.chargesProposerAt(stage)) {
                sendIfOnline(proposerId, "cost.insufficient-other", Map.of("amount", formatAmount(settings.amount()), "player", targetName));
            }
            refundReceipts(receipts, false);
            return ChargeResult.failed();
        }

        return ChargeResult.success(receipts);
    }

    public void notifyChargedPlayers(ChargeResult chargeResult) {
        if (!chargeResult.success()) {
            return;
        }

        for (ChargeReceipt receipt : chargeResult.receipts()) {
            sendIfOnline(receipt.playerId(), "cost.charged", Map.of("amount", formatAmount(receipt.amount())));
        }
    }

    public void refund(ChargeResult chargeResult) {
        refundReceipts(chargeResult.receipts(), true);
    }

    private boolean chargePlayer(UUID playerId, String failurePath, String otherPlayerName, List<ChargeReceipt> receipts) {
        OfflinePlayer player = plugin.getServer().getOfflinePlayer(playerId);
        if (!economyHook.has(player, settings.amount())) {
            sendIfOnline(playerId, failurePath, Map.of("amount", formatAmount(settings.amount()), "player", otherPlayerName));
            return false;
        }

        if (!economyHook.withdraw(player, settings.amount())) {
            sendIfOnline(playerId, "errors.economy-unavailable", Map.of());
            return false;
        }

        receipts.add(new ChargeReceipt(playerId, settings.amount()));
        return true;
    }

    private void refundReceipts(List<ChargeReceipt> receipts, boolean notify) {
        if (economyHook == null) {
            return;
        }

        for (ChargeReceipt receipt : receipts) {
            economyHook.deposit(receipt.playerId(), receipt.amount());
            if (!notify) {
                continue;
            }

            sendIfOnline(receipt.playerId(), "cost.refunded", Map.of("amount", formatAmount(receipt.amount())));
        }
    }

    private void sendIfOnline(UUID playerId, String path, Map<String, String> placeholders) {
        plugin.taskDispatcher().runPlayer(playerId, player -> messages.send(player, path, placeholders));
    }

    private String formatAmount(double amount) {
        if (economyHook != null) {
            return economyHook.format(amount);
        }

        return DECIMAL_FORMAT.format(amount);
    }

    private String displayAmountFor(boolean charged) {
        return charged ? formatAmount(settings.amount()) : messages.raw("format.free-cost", "free");
    }

    public record ChargeResult(boolean success, List<ChargeReceipt> receipts) {
        public static ChargeResult free() {
            return new ChargeResult(true, List.of());
        }

        public static ChargeResult success(List<ChargeReceipt> receipts) {
            return new ChargeResult(true, List.copyOf(receipts));
        }

        public static ChargeResult failed() {
            return new ChargeResult(false, List.of());
        }
    }
}
