package net.taiyou.mythicaugments;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DynamicSkillManager {

    private final MythicAugments plugin;
    private final File skillsFolder;
    private final File generatedFile;
    private final Set<String> registeredHashes = new HashSet<>();

    public DynamicSkillManager(MythicAugments plugin) {
        this.plugin = plugin;
        // plugins/MythicAugments/../MythicMobs/Skills
        this.skillsFolder = new File(plugin.getDataFolder().getParentFile(), "MythicMobs/Skills");
        this.generatedFile = new File(skillsFolder, "mythicaugments_generated.yml");
        loadRegisteredSkills();
    }

    private void loadRegisteredSkills() {
        if (!generatedFile.exists())
            return;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(generatedFile);
        for (String key : config.getKeys(false)) {
            if (key.startsWith("MA_Gen_")) {
                registeredHashes.add(key);
            }
        }
    }

    public String registerSkill(String skillLine) {
        String hash = getMD5(skillLine);
        String skillName = "MA_Gen_" + hash;

        if (registeredHashes.contains(skillName)) {
            return skillName;
        }

        // Register new skill
        try {
            if (!skillsFolder.exists()) {
                skillsFolder.mkdirs();
            }

            YamlConfiguration config = YamlConfiguration.loadConfiguration(generatedFile);

            // Define the skill structure
            // MA_Gen_<Hash>:
            // Skills:
            // - <line>
            List<String> skills = new ArrayList<>();
            skills.add(skillLine);

            config.set(skillName + ".Skills", skills);
            config.save(generatedFile);

            registeredHashes.add(skillName);
            plugin.getLogger().info("Registered new dynamic skill: " + skillName);

            // Reload MythicMobs skills
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mm reload skills");
            });

        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save generated skill: " + e.getMessage());
            e.printStackTrace();
        }

        return skillName;
    }

    private String getMD5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] messageDigest = md.digest(input.getBytes());
            BigInteger no = new BigInteger(1, messageDigest);
            String hashtext = no.toString(16);
            while (hashtext.length() < 32) {
                hashtext = "0" + hashtext;
            }
            return hashtext.substring(0, 8); // Shorten for readability
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
