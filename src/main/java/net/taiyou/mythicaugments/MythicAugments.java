package net.taiyou.mythicaugments;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import java.util.logging.Logger;

public final class MythicAugments extends JavaPlugin {

    private static MythicAugments instance;
    private AugmentManager augmentManager;
    private Logger logger;

    @Override
    public void onEnable() {
        instance = this;
        logger = getLogger();

        if (getServer().getPluginManager().getPlugin("MythicMobs") == null) {
            logger.severe("MythicMobs not found! Disabling MythicAugments.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();

        this.augmentManager = new AugmentManager(this);
        getServer().getPluginManager().registerEvents(new AugmentListener(this), this);
        getCommand("mythicaugments").setExecutor(new AugmentCommand(this));

        // Start Ticker (Runs every 5 ticks = 0.25 second)
        new BukkitRunnable() {
            @Override
            public void run() {
                augmentManager.tick();
            }
        }.runTaskTimer(this, 20L, 5L);

        // Load players if reload happened
        for (Player p : Bukkit.getOnlinePlayers()) {
            augmentManager.loadCache(p);
        }

        logger.info("MythicAugments has been enabled!");
    }

    @Override
    public void onDisable() {
        logger.info("MythicAugments has been disabled.");
    }

    public static MythicAugments getInstance() {
        return instance;
    }

    public AugmentManager getAugmentManager() {
        return augmentManager;
    }
}