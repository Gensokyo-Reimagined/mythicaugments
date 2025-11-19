package net.taiyou.mythicaugments;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class AugmentCommand implements CommandExecutor {

    private final MythicAugments plugin;

    public AugmentCommand(MythicAugments plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
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
                    List<String> skills = plugin.getAugmentManager().getCache().get(((Player) sender).getUniqueId());
                    sender.sendMessage("§7Your active skills: " + (skills != null ? skills.toString() : "None"));
                }
                return true;
            }

            if (args[0].equalsIgnoreCase("info")) {
                if (!(sender instanceof Player)) return true;
                Player p = (Player) sender;
                ItemStack item = p.getInventory().getItemInMainHand();
                if (item == null || item.getType().isAir()) {
                    p.sendMessage("§cHold an item.");
                    return true;
                }

                String mythicId = plugin.getAugmentManager().getMythicID(item);
                p.sendMessage("§e--- Item Info ---");
                p.sendMessage("§7Mythic ID: §f" + (mythicId == null ? "None" : mythicId));

                String skill = plugin.getAugmentManager().getSkillFromItem(item);
                p.sendMessage("§7Mapped Skill: §f" + (skill == null ? "None" : skill));

                String tag = plugin.getAugmentManager().getAugmentTag(item);
                p.sendMessage("§7Tag: §f" + tag);
                return true;
            }

            if (args[0].equalsIgnoreCase("apply") && args.length >= 3) {
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
                String tag = args[2];

                ItemMeta meta = item.getItemMeta();
                meta.getPersistentDataContainer().set(plugin.getAugmentManager().KEY_AUGMENT_TYPE, PersistentDataType.STRING, type);
                meta.getPersistentDataContainer().set(plugin.getAugmentManager().KEY_AUGMENT_TAG, PersistentDataType.STRING, tag);

                item.setItemMeta(meta);
                player.sendMessage("§aApplied augment data: Type=" + type + ", Tag=" + tag);
                return true;
            }
        }

        sender.sendMessage("§eUsage:");
        sender.sendMessage("§e/ma reload");
        sender.sendMessage("§e/ma debug - Toggle console debug logs");
        sender.sendMessage("§e/ma info - Info about item in hand");
        sender.sendMessage("§e/ma apply <type> <tag> - Apply augment data to held item");
        return true;
    }
}