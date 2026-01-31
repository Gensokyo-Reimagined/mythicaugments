package net.taiyou.mythicaugments;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public class AugmentListener implements Listener {

    private final MythicAugments plugin;

    public AugmentListener(MythicAugments plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        givePersistentItem(event.getPlayer());
        plugin.getAugmentManager().loadCache(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getAugmentManager().removeCache(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        givePersistentItem(event.getPlayer());
        plugin.getAugmentManager().loadCache(event.getPlayer());
    }

    private void givePersistentItem(Player player) {
        if (!plugin.getConfig().getBoolean("persistent-item.enabled"))
            return;

        for (ItemStack item : player.getInventory().getContents()) {
            if (isPersistentItem(item))
                return;
        }

        int slot = plugin.getConfig().getInt("persistent-item.slot", 8);
        player.getInventory().setItem(slot, plugin.getAugmentManager().getPersistentItem());
    }

    private boolean isPersistentItem(ItemStack item) {
        if (item == null || !item.hasItemMeta())
            return false;
        return item.getItemMeta().getPersistentDataContainer().has(plugin.getAugmentManager().KEY_MENU_ITEM,
                PersistentDataType.BYTE);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (isPersistentItem(event.getItem())) {
                event.setCancelled(true);
                plugin.getAugmentManager().openAugmentMenu(event.getPlayer());
            }
        }
    }

    // --- INVENTORY HANDLING ---

    @EventHandler(priority = EventPriority.LOW)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player))
            return;
        Player player = (Player) event.getWhoClicked();

        // 1. GLOBAL PROTECTION (Fixes moving issue)
        // Prevents moving persistent item in ANY inventory (Survival inv, Crafting,
        // etc.)
        if (isPersistentItem(event.getCurrentItem())) {
            event.setCancelled(true);
            return;
        }
        // Prevents hotbar swapping the persistent item
        if (event.getHotbarButton() != -1) {
            if (isPersistentItem(player.getInventory().getItem(event.getHotbarButton()))) {
                event.setCancelled(true);
                return;
            }
        }

        // 2. MENU LOGIC
        // Use cached title for performance
        String menuTitle = plugin.getAugmentManager().getMenuTitle();
        if (!event.getView().getTitle().equals(menuTitle))
            return;

        if (event.getClickedInventory() == event.getView().getTopInventory()) {
            int slot = event.getSlot();
            AugmentManager.Socket socket = plugin.getAugmentManager().getSocket(slot);

            if (socket == null) {
                event.setCancelled(true);
                return;
            }

            // Prevent red glass dupe by manually handling swap
            event.setCancelled(true);

            ItemStack cursor = event.getCursor();
            ItemStack current = event.getCurrentItem();

            boolean cursorIsValid = plugin.getAugmentManager().isValidAugment(cursor, socket.requiredType);
            boolean slotHasRealAugment = plugin.getAugmentManager().isValidAugment(current, socket.requiredType);

            // Placing (Cursor -> Slot)
            if (cursorIsValid) {
                event.getView().getTopInventory().setItem(slot, cursor);

                if (slotHasRealAugment) {
                    event.getView().setCursor(current);
                } else {
                    event.getView().setCursor(new ItemStack(Material.AIR));
                }

                player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 1, 1);
                plugin.getAugmentManager().saveAugmentMenu(player, event.getView().getTopInventory());
                return;
            }

            // Taking (Slot -> Cursor)
            if ((cursor == null || cursor.getType() == Material.AIR || cursor.getAmount() == 0) && slotHasRealAugment) {
                event.getView().setCursor(current);
                event.getView().getTopInventory().setItem(slot, socket.placeholder);

                player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 1, 1);
                plugin.getAugmentManager().saveAugmentMenu(player, event.getView().getTopInventory());
                return;
            }
        } else if (event.isShiftClick()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        String menuTitle = plugin.getAugmentManager().getMenuTitle();
        if (!event.getView().getTitle().equals(menuTitle))
            return;

        for (int slot : event.getRawSlots()) {
            if (slot < event.getView().getTopInventory().getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player))
            return;
        Player player = (Player) event.getPlayer();
        String menuTitle = plugin.getAugmentManager().getMenuTitle();

        if (event.getView().getTitle().equals(menuTitle)) {
            plugin.getAugmentManager().saveAugmentMenu(player, event.getInventory());
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (plugin.getConfig().getBoolean("persistent-item.locked")
                && event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            if (isPersistentItem(event.getItemDrop().getItemStack())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (plugin.getConfig().getBoolean("persistent-item.locked")) {
            event.getDrops().removeIf(this::isPersistentItem);
        }
    }
}