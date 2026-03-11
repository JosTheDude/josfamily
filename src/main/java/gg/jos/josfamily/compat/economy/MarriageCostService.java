package gg.jos.josfamily.compat.economy;

import gg.jos.josfamily.JosFamily;
import gg.jos.josfamily.config.MessageService;
import gg.jos.josfamily.config.PluginSettings.MarriageCostChargeMode;
import gg.jos.josfamily.config.PluginSettings.MarriageCostSettings;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.entity.Player;

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

    public ChargeResult charge(Player proposer, Player target) {
        if (!settings.active()) {
            return ChargeResult.free();
        }
        if (economyHook == null) {
            messages.send(target, "errors.economy-unavailable");
            messages.send(proposer, "errors.economy-unavailable");
            return ChargeResult.failed();
        }

        List<ChargeReceipt> receipts = new ArrayList<>();
        return switch (settings.chargeMode()) {
            case PROPOSER -> chargeOnly(proposer, target.getName(), receipts);
            case TARGET -> chargeOnly(target, proposer.getName(), receipts);
            case BOTH -> chargeBoth(proposer, target, receipts);
        };
    }

    public void notifyChargedPlayers(ChargeResult chargeResult) {
        if (!chargeResult.success()) {
            return;
        }

        for (ChargeReceipt receipt : chargeResult.receipts()) {
            Player player = plugin.getServer().getPlayer(receipt.playerId());
            if (player != null && player.isOnline()) {
                messages.send(player, "cost.charged", Map.of("amount", formatAmount(receipt.amount())));
            }
        }
    }

    public void refund(ChargeResult chargeResult) {
        refundReceipts(chargeResult.receipts(), true);
    }

    private ChargeResult chargeOnly(Player payer, String otherPlayerName, List<ChargeReceipt> receipts) {
        if (!chargePlayer(payer, "cost.insufficient-self", otherPlayerName, receipts)) {
            return ChargeResult.failed();
        }

        return ChargeResult.success(receipts);
    }

    private ChargeResult chargeBoth(Player proposer, Player target, List<ChargeReceipt> receipts) {
        if (!chargePlayer(proposer, "cost.insufficient-self", target.getName(), receipts)) {
            return ChargeResult.failed();
        }
        if (!chargePlayer(target, "cost.insufficient-self", proposer.getName(), receipts)) {
            messages.send(proposer, "cost.insufficient-other", Map.of("amount", displayAmount(), "player", target.getName()));
            refundReceipts(receipts, false);
            return ChargeResult.failed();
        }

        return ChargeResult.success(receipts);
    }

    private boolean chargePlayer(Player player, String failurePath, String otherPlayerName, List<ChargeReceipt> receipts) {
        if (!economyHook.has(player, settings.amount())) {
            messages.send(player, failurePath, Map.of("amount", displayAmount(), "player", otherPlayerName));
            return false;
        }

        if (!economyHook.withdraw(player, settings.amount())) {
            messages.send(player, "errors.economy-unavailable");
            return false;
        }

        receipts.add(new ChargeReceipt(player.getUniqueId(), settings.amount()));
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

            Player player = plugin.getServer().getPlayer(receipt.playerId());
            if (player != null && player.isOnline()) {
                messages.send(player, "cost.refunded", Map.of("amount", formatAmount(receipt.amount())));
            }
        }
    }

    private String formatAmount(double amount) {
        if (economyHook != null) {
            return economyHook.format(amount);
        }

        return DECIMAL_FORMAT.format(amount);
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

    public record ChargeReceipt(UUID playerId, double amount) {}
}
