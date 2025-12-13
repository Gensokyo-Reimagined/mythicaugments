package net.taiyou.mythicaugments;

import io.lumine.mythic.bukkit.MythicBukkit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.lumine.mythic.core.items.MythicItem;

public class AugmentManager {

    private final MythicAugments plugin;
    private final DynamicSkillManager dynamicSkillManager;
    private final Map<Integer, Socket> sockets = new HashMap<>();
    private final Map<String, RegisteredAugment> mythicAugmentMap = new HashMap<>();

    // Concurrent map to prevent modification errors during tick
    private final Map<UUID, List<AugmentSkill>> activeSkillCache = new ConcurrentHashMap<>();
    private final Map<UUID, List<AugmentStat>> activeStatCache = new ConcurrentHashMap<>();

    public boolean debugMode = false; // Toggle with /ma debug

    public final NamespacedKey KEY_MENU_ITEM;
    public final NamespacedKey KEY_AUGMENT_TYPE;
    public final NamespacedKey KEY_SAVED_INVENTORY;

    public AugmentManager(MythicAugments plugin) {
        this.plugin = plugin;
        this.dynamicSkillManager = new DynamicSkillManager(plugin);
        this.KEY_MENU_ITEM = new NamespacedKey(plugin, "menu_item");
        this.KEY_AUGMENT_TYPE = new NamespacedKey(plugin, "augment_type");
        this.KEY_SAVED_INVENTORY = new NamespacedKey(plugin, "saved_inv");
        loadSockets();
    }

    public void loadSockets() {
        sockets.clear();
        mythicAugmentMap.clear();

        FileConfiguration config = plugin.getConfig();

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
                // Store keys as lower case for case-insensitive lookup
                mythicAugmentMap.put(key.toLowerCase(), new RegisteredAugment(type));
            }
        }
    }

    // --- Ticking System ---

    private long integrityCheckTimer = 0;

    public void tick() {
        try {
            long currentTime = System.currentTimeMillis();

            // Self-Healing & Safety Net
            integrityCheckTimer++;
            if (integrityCheckTimer >= 40) {
                integrityCheckTimer = 0;
                for (Player player : Bukkit.getOnlinePlayers()) {
                    // Safety Net: Ensure player is loaded
                    if (!loadedPlayers.contains(player.getUniqueId())) {
                        plugin.getLogger().info("[Debug] Safety Net: Loading cache for " + player.getName());
                        loadCache(player);
                    } else {
                        // Integrity Check
                        checkStatIntegrity(player);
                    }
                }
            }

            if (activeSkillCache.isEmpty())
                // Verify if we should return here. If we have stats but no skills, we probably
                // still want to tick?
                // Actually the ticker is for SKILLS. Stats are static. So return is fine here
                // for SKILLS logic.
                return;

            for (Map.Entry<UUID, List<AugmentSkill>> entry : activeSkillCache.entrySet()) {
                Player player = Bukkit.getPlayer(entry.getKey());

                // Cleanup offline players
                if (player == null || !player.isOnline()) {
                    activeSkillCache.remove(entry.getKey());
                    continue;
                }

                List<AugmentSkill> skills = entry.getValue();
                if (skills == null || skills.isEmpty())
                    continue;

                for (AugmentSkill skill : skills) {
                    if (currentTime - skill.getLastExecuted() >= skill.getInterval() * 50L) {
                        executeSkill(player, skill.getSkillLine());
                        skill.setLastExecuted(currentTime);
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error in Augment Ticker: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void checkStatIntegrity(Player player) {
        List<AugmentStat> cachedStats = activeStatCache.get(player.getUniqueId());
        if (cachedStats == null || cachedStats.isEmpty())
            return;

        try {
            // We only check the first stat to save performance. If one is gone, likely all
            // are gone.
            io.lumine.mythic.core.skills.stats.StatRegistry registry = io.lumine.mythic.bukkit.MythicBukkit.inst()
                    .getPlayerManager().getProfile(player).getStatRegistry();

            boolean missing = false;
            for (AugmentStat stat : cachedStats) {
                if (mapStatToAttribute(stat.getStat()) != null)
                    continue; // Skip Bukkit attributes

                io.lumine.mythic.core.skills.stats.StatType type = getStatType(stat.getStat());
                if (type != null) {
                    var statMapOpt = registry.getStatData(type);
                    if (statMapOpt.isPresent()) {
                        var statMap = statMapOpt.get();
                        if (!statMap.getAdditives().containsKey(statSource) &&
                                !statMap.getAdditiveMultipliers().containsKey(statSource)) {
                            missing = true;
                            break;
                        }
                    } else {
                        // StatMap doesn't exist? Then our val is definitely missing.
                        missing = true;
                        break;
                    }
                }
            }

            if (missing) {
                if (debugMode)
                    plugin.getLogger()
                            .warning("[Debug] Stats missing for " + player.getName() + ", re-applying (Self-Healing).");
                applyStats(player, cachedStats);
            }

        } catch (Exception e) {
            // Ignore errors here to prevent console spam
        }
    }

    private void executeSkill(Player player, String skillName) {
        try {
            // Use API Helper for simpler execution
            boolean success = MythicBukkit.inst().getAPIHelper().castSkill(player, skillName);

            if (success) {
                if (debugMode) {
                    // Only log occasionally to avoid spam, or use a cooldown
                }
            } else {
                if (debugMode) {
                    plugin.getLogger().warning("DEBUG: Failed to cast skill: '" + skillName + "'");
                    if (skillName.contains("{") || skillName.contains(" ") || skillName.contains("@")) {
                        plugin.getLogger().warning("-> It looks like you are using an inline mechanic.");
                        plugin.getLogger()
                                .warning("-> The Augment system requires NAMED skills (e.g., 'SnakeSpeedPassive').");
                        plugin.getLogger().warning(
                                "-> Please create a skill file in MythicMobs/Skills/ and reference it in your item.");
                    } else {
                        plugin.getLogger().warning(
                                "-> Ensure the skill '" + skillName + "' exists in your MythicMobs/Skills/ folder.");
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error ticking skill " + skillName + ": " + e.getMessage());
        }
    }

    public void loadCache(Player player) {
        loadedPlayers.add(player.getUniqueId());
        ItemStack[] items = loadPlayerInventory(player);
        recalculatePlayerSkills(player, items);
    }

    public void removeCache(Player player) {
        loadedPlayers.remove(player.getUniqueId());
        activeSkillCache.remove(player.getUniqueId());
        removeStats(player);
        activeStatCache.remove(player.getUniqueId());
    }

    private void recalculatePlayerSkills(Player player, ItemStack[] items) {
        List<AugmentSkill> skillsToRun = new ArrayList<>();
        List<AugmentStat> statsToApply = new ArrayList<>();

        if (items != null) {
            for (ItemStack item : items) {
                if (item == null || item.getType() == Material.AIR)
                    continue;

                List<AugmentSkill> itemSkills = getSkillsFromItem(item);
                if (itemSkills != null && !itemSkills.isEmpty()) {
                    skillsToRun.addAll(itemSkills);
                }

                List<AugmentStat> itemStats = getStatsFromItem(item);
                if (itemStats != null && !itemStats.isEmpty()) {
                    statsToApply.addAll(itemStats);
                }
            }
        }

        // Update Skills
        if (!skillsToRun.isEmpty()) {
            activeSkillCache.put(player.getUniqueId(), skillsToRun);
            if (debugMode)
                plugin.getLogger().info("Loaded skills for " + player.getName() + ": " + skillsToRun.size());
        } else {
            activeSkillCache.remove(player.getUniqueId());
            if (debugMode)
                plugin.getLogger().info("No skills loaded for " + player.getName());
        }

        // Update Stats
        removeStats(player); // Remove old stats first
        if (!statsToApply.isEmpty()) {
            applyStats(player, statsToApply);
            activeStatCache.put(player.getUniqueId(), statsToApply);
            if (debugMode)
                plugin.getLogger().info("Loaded stats for " + player.getName() + ": " + statsToApply.size());
        } else {
            activeStatCache.remove(player.getUniqueId());
        }
    }

    // --- StatSource Implementation ---

    // Track loaded players to ensure we don't miss anyone
    private final Set<UUID> loadedPlayers = ConcurrentHashMap.newKeySet();

    // Ensure statSource is instantiated!
    private final AugmentStatSource statSource = new AugmentStatSource();

    public class AugmentStatSource implements io.lumine.mythic.core.skills.stats.StatSource {
        private final String name = "MythicAugments";

        @Override
        public boolean removeOnReload() {
            return false;
        }

        @Override
        public String toString() {
            return name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            AugmentStatSource that = (AugmentStatSource) o;
            return Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name);
        }
    }

    private void applyStats(Player player, List<AugmentStat> stats) {
        if (debugMode)
            plugin.getLogger().info("Applying stats to " + player.getName() + ": " + stats.size());
        // 1. Aggregate stats to avoid multiple modifiers for the same stat
        Map<String, Double> additiveMap = new HashMap<>();
        Map<String, Double> multiplyMap = new HashMap<>();

        for (AugmentStat stat : stats) {
            String key = stat.getStat().toUpperCase();
            double value = stat.getValue();
            if (stat.getType().equalsIgnoreCase("MULTIPLY")) {
                multiplyMap.put(key, multiplyMap.getOrDefault(key, 0.0) + value);
            } else {
                additiveMap.put(key, additiveMap.getOrDefault(key, 0.0) + value);
            }
        }

        // 2. Apply Bukkit Attributes
        applyBukkitStats(player, additiveMap, multiplyMap);
        // 3. Apply Mythic Stats
        applyMythicStats(player, additiveMap, multiplyMap);
    }

    private void applyBukkitStats(Player player, Map<String, Double> additive, Map<String, Double> multiply) {
        Set<String> processed = new HashSet<>();

        // Helper to process both maps
        processBukkitStatMap(player, additive, AttributeModifier.Operation.ADD_NUMBER, processed);
        processBukkitStatMap(player, multiply, AttributeModifier.Operation.ADD_SCALAR, processed);
    }

    private void processBukkitStatMap(Player player, Map<String, Double> map, AttributeModifier.Operation op,
            Set<String> processed) {
        for (Map.Entry<String, Double> entry : map.entrySet()) {
            String statName = entry.getKey();
            Attribute attribute = mapStatToAttribute(statName);

            if (attribute != null) {
                AttributeInstance instance = player.getAttribute(attribute);
                if (instance != null) {
                    NamespacedKey key = new NamespacedKey(plugin,
                            "augment_" + statName.toLowerCase() + "_" + UUID.randomUUID().toString().substring(0, 8));
                    AttributeModifier modifier = new AttributeModifier(key, entry.getValue(), op,
                            EquipmentSlotGroup.ANY);
                    instance.addModifier(modifier);
                    if (debugMode)
                        plugin.getLogger().info("Applied Bukkit stat: " + statName + " = " + entry.getValue());
                } else {
                    if (debugMode)
                        plugin.getLogger().warning("AttributeInstance null for: " + statName);
                }
            }
        }
    }

    private void applyMythicStats(Player player, Map<String, Double> additive, Map<String, Double> multiply) {
        try {
            io.lumine.mythic.core.skills.stats.StatRegistry registry = io.lumine.mythic.bukkit.MythicBukkit.inst()
                    .getPlayerManager().getProfile(player).getStatRegistry();

            // Apply Additive
            for (Map.Entry<String, Double> entry : additive.entrySet()) {
                if (mapStatToAttribute(entry.getKey()) != null)
                    continue; // Skip if handled by Bukkit

                io.lumine.mythic.core.skills.stats.StatType type = getStatType(entry.getKey());
                if (type != null) {
                    registry.putValue(type, statSource, io.lumine.mythic.core.skills.stats.StatModifierType.ADDITIVE,
                            entry.getValue());
                    if (debugMode)
                        plugin.getLogger().info("Applied Mythic stat: " + entry.getKey() + " = " + entry.getValue());
                }
            }

            // Apply Multiply
            for (Map.Entry<String, Double> entry : multiply.entrySet()) {
                if (mapStatToAttribute(entry.getKey()) != null)
                    continue; // Skip if handled by Bukkit

                io.lumine.mythic.core.skills.stats.StatType type = getStatType(entry.getKey());
                if (type != null) {
                    registry.putValue(type, statSource,
                            io.lumine.mythic.core.skills.stats.StatModifierType.ADDITIVE_MULTIPLIER, entry.getValue());
                    if (debugMode)
                        plugin.getLogger()
                                .info("Applied Mythic stat (Mult): " + entry.getKey() + " = " + entry.getValue());
                }
            }

            // Force update
            registry.updateDirtyStats();

        } catch (Exception e) {
            if (debugMode) {
                plugin.getLogger().warning("Failed to apply Mythic stats: " + e.getMessage());
            }
        }
    }

    private io.lumine.mythic.core.skills.stats.StatType getStatType(String name) {
        io.lumine.mythic.core.skills.stats.StatType type = io.lumine.mythic.bukkit.MythicBukkit.inst().getStatManager()
                .getStat(name).orElse(null);
        if (type != null)
            return type;

        // Fallback: Case-insensitive search
        for (Map.Entry<String, io.lumine.mythic.core.skills.stats.StatType> entry : io.lumine.mythic.bukkit.MythicBukkit
                .inst().getStatManager().getStats().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }

        if (debugMode)
            plugin.getLogger().warning("Unknown Mythic stat: " + name);
        return null;
    }

    private void removeStats(Player player) {
        try {
            // 1. Remove Bukkit Modifiers
            List<Attribute> attributes = new ArrayList<>();
            attributes.add(Attribute.MAX_HEALTH);
            attributes.add(Attribute.ATTACK_DAMAGE);
            attributes.add(Attribute.MOVEMENT_SPEED);
            attributes.add(Attribute.ARMOR);
            attributes.add(Attribute.ARMOR_TOUGHNESS);
            attributes.add(Attribute.LUCK);
            attributes.add(Attribute.KNOCKBACK_RESISTANCE);
            try {
                attributes.add(Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.attack_speed")));
            } catch (Exception e) {
                if (debugMode)
                    plugin.getLogger().warning("Could not find generic.attack_speed attribute.");
            }

            for (Attribute attr : attributes) {
                if (attr == null)
                    continue;
                AttributeInstance instance = player.getAttribute(attr);
                if (instance != null) {
                    List<AttributeModifier> toRemove = new ArrayList<>();
                    for (AttributeModifier modifier : instance.getModifiers()) {
                        if (modifier.getKey().getNamespace().equals(plugin.getName().toLowerCase())) {
                            toRemove.add(modifier);
                        }
                    }
                    for (AttributeModifier modifier : toRemove) {
                        instance.removeModifier(modifier);
                    }
                }
            }

            // 2. Remove Mythic Stats
            io.lumine.mythic.core.skills.stats.StatRegistry registry = io.lumine.mythic.bukkit.MythicBukkit.inst()
                    .getPlayerManager().getProfile(player).getStatRegistry();

            List<AugmentStat> cachedStats = activeStatCache.get(player.getUniqueId());
            if (cachedStats != null) {
                for (AugmentStat stat : cachedStats) {
                    if (mapStatToAttribute(stat.getStat()) != null)
                        continue; // Skip if handled by Bukkit

                    io.lumine.mythic.core.skills.stats.StatType type = getStatType(stat.getStat());
                    if (type != null) {
                        registry.removeValue(type, statSource);
                    }
                }
            }
        } catch (Exception e) {
            if (debugMode)
                plugin.getLogger().warning("Error removing stats: " + e.getMessage());
        }
    }

    private Attribute mapStatToAttribute(String stat) {
        String key = stat.toUpperCase();
        Attribute attr = null;
        switch (key) {
            case "HEALTH":
                attr = Attribute.MAX_HEALTH;
                break;
            case "DAMAGE":
                attr = Attribute.ATTACK_DAMAGE;
                break;
            case "SPEED":
                attr = Attribute.MOVEMENT_SPEED;
                break;
            case "ARMOR":
            case "DEFENSE":
                attr = Attribute.ARMOR;
                break;
            case "TOUGHNESS":
                attr = Attribute.ARMOR_TOUGHNESS;
                break;
            case "LUCK":
                attr = Attribute.LUCK;
                break;
            case "KNOCKBACK_RESISTANCE":
                attr = Attribute.KNOCKBACK_RESISTANCE;
                break;
            case "ATTACK_SPEED":
                try {
                    attr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.attack_speed"));
                } catch (Exception e) {
                    attr = null;
                }
                break;
            default:
                attr = null;
                break;
        }
        return attr;
    }

    // Helper to get skill from Mythic Item
    public List<AugmentSkill> getSkillsFromItem(ItemStack item) {
        String mythicId = getMythicID(item);
        if (mythicId == null)
            return null;

        Optional<MythicItem> mythicItemOpt = MythicBukkit.inst().getItemManager().getItem(mythicId);
        if (!mythicItemOpt.isPresent())
            return null;

        MythicItem mythicItem = mythicItemOpt.get();
        List<String> skills = mythicItem.getConfig().getStringList("Skills");
        if (skills == null || skills.isEmpty())
            return null;

        List<AugmentSkill> augmentSkills = new ArrayList<>();
        Pattern timerPattern = Pattern.compile("~onTimer:(\\d+)");

        for (String skillLine : skills) {
            Matcher matcher = timerPattern.matcher(skillLine);
            if (matcher.find()) {
                try {
                    int interval = Integer.parseInt(matcher.group(1));
                    String cleanSkill = skillLine.replaceAll("~onTimer:\\d+", "").trim();

                    // Check for inline mechanics
                    if (cleanSkill.contains("{") || cleanSkill.contains(" ") || cleanSkill.contains("@")) {
                        // Register dynamic skill
                        String registeredName = dynamicSkillManager.registerSkill(cleanSkill);
                        augmentSkills.add(new AugmentSkill(registeredName, interval));
                    } else {
                        augmentSkills.add(new AugmentSkill(cleanSkill, interval));
                    }
                } catch (NumberFormatException e) {
                    // Ignore
                }
            }
        }
        return augmentSkills;
    }

    public List<AugmentStat> getStatsFromItem(ItemStack item) {
        String mythicId = getMythicID(item);
        if (mythicId == null)
            return null;

        Optional<MythicItem> mythicItemOpt = MythicBukkit.inst().getItemManager().getItem(mythicId);
        if (!mythicItemOpt.isPresent())
            return null;

        MythicItem mythicItem = mythicItemOpt.get();
        List<String> stats = mythicItem.getConfig().getStringList("Stats");
        if (stats == null || stats.isEmpty())
            return null;

        List<AugmentStat> augmentStats = new ArrayList<>();
        for (String statLine : stats) {
            // Format: STAT VALUE [TYPE]
            // Example: HEALTH 20to30 ADDITIVE
            String[] parts = statLine.split("\\s+");
            if (parts.length >= 2) {
                String stat = parts[0];
                String valueStr = parts[1];
                String type = (parts.length > 2) ? parts[2] : "ADDITIVE";

                double value = 0;
                if (valueStr.contains("to")) {
                    String[] range = valueStr.split("to");
                    try {
                        double min = Double.parseDouble(range[0]);
                        double max = Double.parseDouble(range[1]);
                        value = (min + max) / 2.0; // Average for now
                    } catch (Exception e) {
                    }
                } else {
                    try {
                        value = Double.parseDouble(valueStr);
                    } catch (Exception e) {
                    }
                }

                augmentStats.add(new AugmentStat(stat, value, type));
            }
        }
        return augmentStats;
    }

    // --- Menus ---

    public void openAugmentMenu(Player player) {
        FileConfiguration config = plugin.getConfig();
        int rows = config.getInt("menu.rows", 3);

        Inventory inv = Bukkit.createInventory(player, rows * 9,
                LegacyComponentSerializer.legacyAmpersand().deserialize(getMenuTitle()));

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
        ItemStack[] allItems = new ItemStack[54]; // Max size

        for (Map.Entry<Integer, Socket> entry : sockets.entrySet()) {
            int slot = entry.getKey();
            Socket socket = entry.getValue();
            ItemStack item = inv.getItem(slot);

            if (isValidAugment(item, socket.requiredType)) {
                itemsToSave.put(slot, item);
                allItems[slot] = item;
            }
        }

        savePlayerInventory(player, itemsToSave);
        recalculatePlayerSkills(player, allItems);
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

    public Map<UUID, List<AugmentSkill>> getCache() {
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
        return item;
    }

    private ItemStack getFillerItem() {
        FileConfiguration config = plugin.getConfig();
        String matName = config.getString("menu.filler.material", "GRAY_STAINED_GLASS_PANE");
        Material mat = getMaterialSafe(matName, Material.GRAY_STAINED_GLASS_PANE);

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(colorComponent(config.getString("menu.filler.name", " ")));
            item.setItemMeta(meta);
        }
        return item;
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
        return color(plugin.getConfig().getString("menu.title", "Augments"));
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

        public RegisteredAugment(String type) {
            this.type = type;
        }
    }
}