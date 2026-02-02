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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public class AugmentListener implements Listener {

    private final MythicAugments plugin;

    public AugmentListener(MythicAugments plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Ensure inventory fully loads.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            givePersistentItem(event.getPlayer());
            plugin.getAugmentManager().loadCache(event.getPlayer());
        }, 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getAugmentManager().removeCache(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            givePersistentItem(event.getPlayer());
            plugin.getAugmentManager().loadCache(event.getPlayer());
        }, 1L);
    }

    private void givePersistentItem(Player player) {
        if (!plugin.getConfig().getBoolean("persistent-item.enabled"))
            return;

        // Remove duplicate persistent items.
        boolean found = false;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (isPersistentItem(item)) {
                if (found) {
                    player.getInventory().setItem(i, null);
                } else {
                    found = true;
                }
            }
        }

        if (found)
            return;

        int slot = plugin.getConfig().getInt("persistent-item.slot", 8);
        ItemStack item = plugin.getAugmentManager().getPersistentItem();

        if (player.getInventory().getItem(slot) == null) {
            player.getInventory().setItem(slot, item);
        } else {
            player.getInventory().addItem(item);
        }
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

    @EventHandler(priority = EventPriority.LOW)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player))
            return;
        Player player = (Player) event.getWhoClicked();

        // Check persistent item interaction.
        boolean currentIsPersistent = isPersistentItem(event.getCurrentItem());
        boolean cursorIsPersistent = isPersistentItem(event.getCursor());
        boolean numberKeyIsPersistent = (event.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY
                && event.getHotbarButton() != -1
                && isPersistentItem(player.getInventory().getItem(event.getHotbarButton())));

        if (currentIsPersistent || cursorIsPersistent || numberKeyIsPersistent) {

            // Block all dropping actions.
            if (event.getAction().name().contains("DROP")) {
                event.setCancelled(true);
                return;
            }

            Inventory clickedInv = event.getClickedInventory();
            Inventory topInv = event.getView().getTopInventory();

            // Check if external container.
            boolean isTopInvPlayer = (topInv.getType() == org.bukkit.event.inventory.InventoryType.CRAFTING
                    || topInv.getType() == org.bukkit.event.inventory.InventoryType.PLAYER
                    || topInv.getType() == org.bukkit.event.inventory.InventoryType.CREATIVE);

            // Click outside window.
            if (clickedInv == null) {
                event.setCancelled(true);
                return;
            }

            // Block external inventory interaction.
            if (!isTopInvPlayer) {
                // Persistent item in external.
                if (clickedInv == topInv) {
                    if (cursorIsPersistent || numberKeyIsPersistent) {
                        event.setCancelled(true);
                        return;
                    }
                }

                // Shift-click player to external.
                if (event.isShiftClick() && clickedInv != topInv) {
                    event.setCancelled(true);
                    return;
                }

                // Swap player to external.
                if (clickedInv == topInv && numberKeyIsPersistent) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        // Verify correct menu title.
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

            event.setCancelled(true);

            ItemStack cursor = event.getCursor();
            ItemStack current = event.getCurrentItem();

            boolean cursorIsValid = plugin.getAugmentManager().isValidAugment(cursor, socket.requiredType);
            boolean slotHasRealAugment = plugin.getAugmentManager().isValidAugment(current, socket.requiredType);

            // Handle placing into slot.
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

            // Handle taking from slot.
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
        // Check persistent item drag.
        if (isPersistentItem(event.getOldCursor())) {
            Inventory topInv = event.getView().getTopInventory();
            boolean isTopInvPlayer = (topInv.getType() == org.bukkit.event.inventory.InventoryType.CRAFTING
                    || topInv.getType() == org.bukkit.event.inventory.InventoryType.PLAYER
                    || topInv.getType() == org.bukkit.event.inventory.InventoryType.CREATIVE);

            if (!isTopInvPlayer) {
                for (int slot : event.getRawSlots()) {
                    if (slot < topInv.getSize()) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }
        }

        // Verify correct menu title.
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