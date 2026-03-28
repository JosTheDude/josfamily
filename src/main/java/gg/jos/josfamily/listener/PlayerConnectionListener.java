package gg.jos.josfamily.listener;

import gg.jos.josfamily.JosFamily;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerConnectionListener implements Listener {
    private final JosFamily plugin;

    public PlayerConnectionListener(JosFamily plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.marriageService().clearPendingState(event.getPlayer().getUniqueId(), true);
    }
}
