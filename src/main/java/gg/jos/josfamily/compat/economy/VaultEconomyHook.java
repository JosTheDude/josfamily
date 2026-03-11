package gg.jos.josfamily.compat.economy;

import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class VaultEconomyHook {
    private final Economy economy;

    private VaultEconomyHook(Economy economy) {
        this.economy = economy;
    }

    public static VaultEconomyHook find() {
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration == null || registration.getProvider() == null) {
            return null;
        }

        return new VaultEconomyHook(registration.getProvider());
    }

    public boolean has(Player player, double amount) {
        return economy.has(player, amount);
    }

    public boolean withdraw(Player player, double amount) {
        EconomyResponse response = economy.withdrawPlayer(player, amount);
        return response.transactionSuccess();
    }

    public void deposit(UUID playerId, double amount) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        economy.depositPlayer(player, amount);
    }

    public String format(double amount) {
        return economy.format(amount);
    }
}
