package net.taiyou.mythicaugments;

import io.lumine.mythic.bukkit.MythicBukkit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AugmentManager {

    private final MythicAugments plugin;
    private final Map<Integer, Socket> sockets = new HashMap<>();
    private final Map<String, RegisteredAugment> mythicAugmentMap = new HashMap<>();

    // Concurrent map to prevent modification errors during tick
    private final Map<UUID, List<String>> activeSkillCache = new ConcurrentHashMap<>();

    public boolean debugMode = false; // Toggle with /ma debug

    public final NamespacedKey KEY_MENU_ITEM;
    public final NamespacedKey KEY_AUGMENT_TYPE;
    public final NamespacedKey KEY_AUGMENT_TAG;
    public final NamespacedKey KEY_SAVED_INVENTORY;

    // Caches
    private ItemStack cachedFillerItem;
    private ItemStack cachedPersistentItem;
    private String cachedMenuTitle;

    public AugmentManager(MythicAugments plugin) {
        this.plugin = plugin;
        this.KEY_MENU_ITEM = new NamespacedKey(plugin, "menu_item");
        this.KEY_AUGMENT_TYPE = new NamespacedKey(plugin, "augment_type");
        this.KEY_AUGMENT_TAG = new NamespacedKey(plugin, "augment_tag");
        this.KEY_SAVED_INVENTORY = new NamespacedKey(plugin, "saved_inv");
        loadSockets();
    }

    public void loadSockets() {
        sockets.clear();
        mythicAugmentMap.clear();
        cachedFillerItem = null;
        cachedPersistentItem = null;

        FileConfiguration config = plugin.getConfig();
        cachedMenuTitle = color(config.getString("menu.title", "Augments"));

        // 1. Load Sockets
        ConfigurationSection section = config.getConfigurationSection("sockets");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    int slot = Integer.parseInt(key);
                    String type = section.getString(key + ".type");

                    String matName = section.getString(key + ".placeholder.material", "BARRIER");
                    Material mat = getMaterialSafe(matName, Material.BARRIER);

                    String name = section.getString(key + ".placeholder.name", "Slot");
                    List<String> lore = section.getStringList(key + ".placeholder.lore");

                    ItemStack placeholder = new ItemStack(mat);
                    ItemMeta meta = placeholder.getItemMeta();
                    if (meta != null) {
                        meta.displayName(colorComponent(name));
                        List<Component> coloredLore = new ArrayList<>();
                        if (lore != null)
                            lore.forEach(l -> coloredLore.add(colorComponent(l)));
                        meta.lore(coloredLore);
                        placeholder.setItemMeta(meta);
                    }

                    sockets.put(slot, new Socket(slot, type, placeholder));
                } catch (Exception e) {
                    plugin.getLogger().warning("Error loading socket " + key + ": " + e.getMessage());
                }
            }
        }

        // 2. Load Manual Overrides
        ConfigurationSection mythicSection = config.getConfigurationSection("mythic-items");
        if (mythicSection != null) {
            for (String key : mythicSection.getKeys(false)) {
                String type = mythicSection.getString(key + ".type");
                String tag = mythicSection.getString(key + ".tag");
                String passiveSkill = mythicSection.getString(key + ".passive-skill");
                // Store keys as lower case for case-insensitive lookup
                mythicAugmentMap.put(key.toLowerCase(), new RegisteredAugment(type, tag, passiveSkill));
            }
        }
    }

    // --- Ticking System ---

    public void tick() {
        try {
            if (activeSkillCache.isEmpty())
                return;

            for (Map.Entry<UUID, List<String>> entry : activeSkillCache.entrySet()) {
                Player player = Bukkit.getPlayer(entry.getKey());

                // Cleanup offline players
                if (player == null || !player.isOnline()) {
                    activeSkillCache.remove(entry.getKey());
                    continue;
                }

                List<String> skills = entry.getValue();
                if (skills == null || skills.isEmpty())
                    continue;

                for (String skillName : skills) {
                    executeSkill(player, skillName);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error in Augment Ticker: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void executeSkill(Player player, String skillName) {
        try {
            // Use API Helper for simpler execution
            boolean success = MythicBukkit.inst().getAPIHelper().castSkill(player, skillName);

            if (success) {
                if (debugMode) {
                    // Only log occasionally to avoid spam, or use a cooldown
                    // plugin.getLogger().info("DEBUG: Ticked " + skillName + " for " +
                    // player.getName());
                }
            } else {
                if (debugMode) {
                    plugin.getLogger().warning("DEBUG: Failed to cast skill: " + skillName);
                    plugin.getLogger()
                            .warning("-> Ensure you have created a file in 'plugins/MythicMobs/Skills/' with:");
                    plugin.getLogger().warning("-> " + skillName + ":");
                    plugin.getLogger().warning("->   Skills:");
                    plugin.getLogger().warning("->   - ... your mechanics here ...");
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error ticking skill " + skillName + ": " + e.getMessage());
        }
    }

    public void loadCache(Player player) {
        ItemStack[] items = loadPlayerInventory(player);
        recalculatePlayerSkills(player, items);
    }

    public void removeCache(Player player) {
        activeSkillCache.remove(player.getUniqueId());
    }

    private void recalculatePlayerSkills(Player player, ItemStack[] items) {
        List<String> skillsToRun = new ArrayList<>();

        if (items != null) {
            for (ItemStack item : items) {
                if (item == null || item.getType() == Material.AIR)
                    continue;

                String skill = getSkillFromItem(item);
                if (skill != null) {
                    skillsToRun.add(skill);
                } else {
                    if (debugMode) {
                        String mythicId = getMythicID(item);
                        if (mythicId != null) {
                            plugin.getLogger().info("DEBUG: Item " + mythicId + " has no mapped skill.");
                        }
                    }
                }
            }
        }

        if (!skillsToRun.isEmpty()) {
            activeSkillCache.put(player.getUniqueId(), skillsToRun);
            if (debugMode)
                plugin.getLogger().info("Loaded skills for " + player.getName() + ": " + skillsToRun);
        } else {
            activeSkillCache.remove(player.getUniqueId());
            if (debugMode)
                plugin.getLogger().info("No skills loaded for " + player.getName());
        }
    }

    // Helper to get skill from ANY item (Mythic or Vanilla with Tags)
    public String getSkillFromItem(ItemStack item) {
        // 1. Check Mythic ID
        String mythicId = getMythicID(item);
        if (mythicId != null && mythicAugmentMap.containsKey(mythicId.toLowerCase())) {
            return mythicAugmentMap.get(mythicId.toLowerCase()).passiveSkill;
        }
        // 2. Check if it has a manual tag that maps to something (Not implemented in
        // config yet, but good for future)
        return null;
    }

    // --- Menus ---

    public void openAugmentMenu(Player player) {
        FileConfiguration config = plugin.getConfig();
        int rows = config.getInt("menu.rows", 3);

        Inventory inv = Bukkit.createInventory(player, rows * 9,
                LegacyComponentSerializer.legacyAmpersand().deserialize(cachedMenuTitle));

        ItemStack filler = getFillerItem();
        for (int i = 0; i < inv.getSize(); i++) {
            if (!sockets.containsKey(i)) {
                inv.setItem(i, filler);
            } else {
                inv.setItem(i, sockets.get(i).placeholder);
            }
        }

        ItemStack[] savedItems = loadPlayerInventory(player);
        if (savedItems != null) {
            for (int i = 0; i < savedItems.length; i++) {
                if (savedItems[i] != null && sockets.containsKey(i)) {
                    inv.setItem(i, savedItems[i]);
                }
            }
        }

        player.openInventory(inv);
    }

    public void saveAugmentMenu(Player player, Inventory inv) {
        Map<Integer, ItemStack> itemsToSave = new HashMap<>();
        Set<String> activeTags = new HashSet<>();
        ItemStack[] allItems = new ItemStack[54]; // Max size

        for (Map.Entry<Integer, Socket> entry : sockets.entrySet()) {
            int slot = entry.getKey();
            Socket socket = entry.getValue();
            ItemStack item = inv.getItem(slot);

            if (isValidAugment(item, socket.requiredType)) {
                itemsToSave.put(slot, item);
                allItems[slot] = item;

                String tag = getAugmentTag(item);
                if (tag != null)
                    activeTags.add(tag);
            }
        }

        savePlayerInventory(player, itemsToSave);
        updatePlayerTags(player, activeTags);
        recalculatePlayerSkills(player, allItems);
    }

    private void updatePlayerTags(Player player, Set<String> newTags) {
        Set<String> tagsToRemove = new HashSet<>();
        for (String t : player.getScoreboardTags()) {
            if (t.startsWith("augment.")) {
                tagsToRemove.add(t);
            }
        }
        tagsToRemove.forEach(player::removeScoreboardTag);
        newTags.forEach(player::addScoreboardTag);
    }

    public boolean isValidAugment(ItemStack item, String requiredType) {
        if (item == null || !item.hasItemMeta())
            return false;
        if (requiredType == null)
            return false;

        // 1. Check NBT (Manual /ma apply)
        String pdcType = item.getItemMeta().getPersistentDataContainer().get(KEY_AUGMENT_TYPE,
                PersistentDataType.STRING);
        if (pdcType != null && pdcType.equalsIgnoreCase(requiredType)) {
            return true;
        }

        // 2. Check Mythic Config
        String mythicId = getMythicID(item);
        if (mythicId != null) {
            RegisteredAugment augment = mythicAugmentMap.get(mythicId.toLowerCase());
            if (augment != null && augment.type.equalsIgnoreCase(requiredType)) {
                return true;
            }
        }

        return false;
    }

    public String getAugmentTag(ItemStack item) {
        if (item == null || !item.hasItemMeta())
            return null;

        String pdcTag = item.getItemMeta().getPersistentDataContainer().get(KEY_AUGMENT_TAG, PersistentDataType.STRING);
        if (pdcTag != null)
            return pdcTag;

        String mythicId = getMythicID(item);
        if (mythicId != null && mythicAugmentMap.containsKey(mythicId.toLowerCase())) {
            return mythicAugmentMap.get(mythicId.toLowerCase()).tag;
        }

        return null;
    }

    public String getMythicID(ItemStack item) {
        if (item == null)
            return null;
        return MythicBukkit.inst().getItemManager().getMythicTypeFromItem(item);
    }

    public Socket getSocket(int slot) {
        return sockets.get(slot);
    }

    public Map<UUID, List<String>> getCache() {
        return activeSkillCache;
    }

    // --- Serialization ---

    @SuppressWarnings("deprecation")
    private void savePlayerInventory(Player player, Map<Integer, ItemStack> items) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);

            dataOutput.writeInt(items.size());
            for (Map.Entry<Integer, ItemStack> entry : items.entrySet()) {
                dataOutput.writeInt(entry.getKey());
                dataOutput.writeObject(entry.getValue());
            }

            dataOutput.close();
            String base64 = Base64Coder.encodeLines(outputStream.toByteArray());

            player.getPersistentDataContainer().set(KEY_SAVED_INVENTORY, PersistentDataType.STRING, base64);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("deprecation")
    private ItemStack[] loadPlayerInventory(Player player) {
        if (!player.getPersistentDataContainer().has(KEY_SAVED_INVENTORY, PersistentDataType.STRING))
            return null;

        String base64 = player.getPersistentDataContainer().get(KEY_SAVED_INVENTORY, PersistentDataType.STRING);
        ItemStack[] items = new ItemStack[54];

        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64Coder.decodeLines(base64));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);

            int count = dataInput.readInt();
            for (int i = 0; i < count; i++) {
                int slot = dataInput.readInt();
                ItemStack item = (ItemStack) dataInput.readObject();
                items[slot] = item;
            }

            dataInput.close();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load inventory for " + player.getName());
        }
        return items;
    }

    // --- Utils ---

    public ItemStack getPersistentItem() {
        if (cachedPersistentItem != null)
            return cachedPersistentItem.clone();

        FileConfiguration config = plugin.getConfig();
        String matName = config.getString("persistent-item.material", "ENDER_CHEST");
        Material mat = getMaterialSafe(matName, Material.ENDER_CHEST);

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(colorComponent(config.getString("persistent-item.name", "Menu")));
            List<Component> l = new ArrayList<>();
            config.getStringList("persistent-item.lore").forEach(s -> l.add(colorComponent(s)));
            meta.lore(l);
            meta.getPersistentDataContainer().set(KEY_MENU_ITEM, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        cachedPersistentItem = item;
        return item.clone();
    }

    private ItemStack getFillerItem() {
        if (cachedFillerItem != null)
            return cachedFillerItem.clone();

        FileConfiguration config = plugin.getConfig();
        String matName = config.getString("menu.filler.material", "GRAY_STAINED_GLASS_PANE");
        Material mat = getMaterialSafe(matName, Material.GRAY_STAINED_GLASS_PANE);

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(colorComponent(config.getString("menu.filler.name", " ")));
            item.setItemMeta(meta);
        }
        cachedFillerItem = item;
        return item.clone();
    }

    private Material getMaterialSafe(String name, Material def) {
        try {
            return Material.valueOf(name.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid material in config: '" + name + "'. Defaulting to " + def.name());
            return def;
        }
    }

    public String color(String s) {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', s);
    }

    public Component colorComponent(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    public String getMenuTitle() {
        return cachedMenuTitle;
    }

    public static class Socket {
        int slot;
        String requiredType;
        ItemStack placeholder;

        public Socket(int slot, String requiredType, ItemStack placeholder) {
            this.slot = slot;
            this.requiredType = requiredType;
            this.placeholder = placeholder;
        }
    }

    public static class RegisteredAugment {
        String type;
        String tag;
        String passiveSkill;

        public RegisteredAugment(String type, String tag, String passiveSkill) {
            this.type = type;
            this.tag = tag;
            this.passiveSkill = passiveSkill;
        }
    }
}