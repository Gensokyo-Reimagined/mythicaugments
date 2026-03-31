package net.taiyou.mythicaugments;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;


public class AugmentCommand implements CommandExecutor {

    private final MythicAugments plugin;

    public AugmentCommand(MythicAugments plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (!sender.hasPermission("mythicaugments.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }

        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("reload")) {
                plugin.reloadConfig();
                plugin.getAugmentManager().loadSockets();
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    plugin.getAugmentManager().loadCache(p);
                }
                sender.sendMessage("§aMythicAugments configuration reloaded.");
                return true;
            }

            if (args[0].equalsIgnoreCase("debug")) {
                plugin.getAugmentManager().debugMode = !plugin.getAugmentManager().debugMode;
                sender.sendMessage("§eDebug mode: " + plugin.getAugmentManager().debugMode);
                if (sender instanceof Player) {
                    AugmentSkillHolder holder = plugin.getAugmentManager().getSkillHolders()
                            .get(((Player) sender).getUniqueId());
                    if (holder != null) {
                        sender.sendMessage("§7Augment skill holder registered: §atrue");
                    } else {
                        sender.sendMessage("§7Augment skill holder registered: §cfalse");
                    }
                }
                return true;
            }

            if (args[0].equalsIgnoreCase("info")) {
                if (!(sender instanceof Player))
                    return true;
                Player p = (Player) sender;
                ItemStack item = p.getInventory().getItemInMainHand();
                if (item == null || item.getType().isAir()) {
                    p.sendMessage("§cHold an item.");
                    return true;
                }

                String mythicId = plugin.getAugmentManager().getMythicID(item);
                p.sendMessage("§e--- Item Info ---");
                p.sendMessage("§7Mythic ID: §f" + (mythicId == null ? "None" : mythicId));

                p.sendMessage("§7Skills: §f(parsed at equip time via MythicMobs)");
                return true;
            }

            if (args[0].equalsIgnoreCase("apply") && args.length >= 2) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("Players only.");
                    return true;
                }
                Player player = (Player) sender;
                ItemStack item = player.getInventory().getItemInMainHand();

                if (item.getType().isAir()) {
                    player.sendMessage("§cHold an item.");
                    return true;
                }

                String type = args[1];

                ItemMeta meta = item.getItemMeta();
                meta.getPersistentDataContainer().set(plugin.getAugmentManager().KEY_AUGMENT_TYPE,
                        PersistentDataType.STRING, type);

                item.setItemMeta(meta);
                player.sendMessage("§aApplied augment data: Type=" + type);
                return true;
            }
        }

        sender.sendMessage("§eUsage:");
        sender.sendMessage("§e/ma reload");
        sender.sendMessage("§e/ma debug - Toggle console debug logs");
        sender.sendMessage("§e/ma info - Info about item in hand");
        sender.sendMessage("§e/ma apply <type> - Apply augment data to held item");
        return true;
    }
}