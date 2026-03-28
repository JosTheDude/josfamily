package gg.jos.josfamily.listener;

import gg.jos.josfamily.JosFamily;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class MarriageRingListener implements Listener {
    private final JosFamily plugin;

    public MarriageRingListener(JosFamily plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        EquipmentSlot hand = event.getHand();
        ItemStack itemStack = event.getItem();
        if (hand == null || itemStack == null || !plugin.marriageRingService().isMarriageRing(itemStack)) {
            return;
        }

        event.setCancelled(true);
        plugin.marriageRingService().activateRing(event.getPlayer(), itemStack.clone());
    }
}
