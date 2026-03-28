package gg.jos.josfamily.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class FoliaTaskDispatcher {
    private final JavaPlugin plugin;

    public FoliaTaskDispatcher(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void runAsync(Runnable runnable) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> runnable.run());
    }

    public void runGlobal(Runnable runnable) {
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> runnable.run());
    }

    public void runGlobalLater(long delayTicks, Runnable runnable) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> runnable.run(), delayTicks);
    }

    public void runEntity(Entity entity, Runnable runnable) {
        entity.getScheduler().run(plugin, task -> runnable.run(), () -> { });
    }

    public void runPlayer(UUID playerId, Consumer<Player> consumer) {
        runGlobal(() -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                return;
            }

            runEntity(player, () -> consumer.accept(player));
        });
    }

    public ScheduledTask runAsyncLater(long delay, TimeUnit unit, Runnable runnable) {
        return Bukkit.getAsyncScheduler().runDelayed(plugin, task -> runnable.run(), delay, unit);
    }
}
