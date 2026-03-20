package dev.oakheart.regionmusic.config;

import dev.oakheart.regionmusic.RegionConfig;
import dev.oakheart.regionmusic.RegionConfig.PlaybackOrder;
import dev.oakheart.regionmusic.RegionConfig.VariantType;
import dev.oakheart.regionmusic.RegionMusic;
import dev.oakheart.regionmusic.RegionTrack;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class ConfigManager {

    private final RegionMusic plugin;
    private final Logger logger;
    private final File configFile;
    private FileConfiguration config;

    // General settings
    private boolean debug;
    private int checkInterval;
    private boolean eventPlayerJoin;
    private boolean eventPlayerTeleport;
    private boolean eventPlayerRespawn;
    private boolean eventPlayerChangeWorld;

    // New 2.0 settings
    private int transitionDelay;
    private boolean stopVanillaMusic;
    private String nowPlayingDisplay;
    private String musicStoppedDisplay;

    // Cached region data: world -> region -> RegionConfig
    private Map<String, Map<String, RegionConfig>> regionData = Map.of();

    public ConfigManager(RegionMusic plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
    }

    public void load() {
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }

        config = YamlConfiguration.loadConfiguration(configFile);
        mergeDefaults();
        validate(config);
        cacheValues();
    }

    public boolean reload() {
        FileConfiguration newConfig = YamlConfiguration.loadConfiguration(configFile);

        if (!validate(newConfig)) {
            logger.warning("Configuration reload failed validation. Keeping previous configuration.");
            return false;
        }

        this.config = newConfig;
        cacheValues();
        logger.info("Configuration reloaded successfully.");
        return true;
    }

    private void mergeDefaults() {
        var resource = plugin.getResource("config.yml");
        if (resource == null) return;

        FileConfiguration defaults;
        try (var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            logger.warning("Failed to read default config from JAR: " + e.getMessage());
            return;
        }

        config.setDefaults(defaults);

        if (hasNewKeys(defaults)) {
            config.options().copyDefaults(true);
            try {
                config.save(configFile);
            } catch (IOException e) {
                logger.warning("Failed to save config with new defaults: " + e.getMessage());
            }
        }
    }

    private boolean hasNewKeys(FileConfiguration defaults) {
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) continue;
            if (!config.contains(key, true)) return true;
        }
        return false;
    }

    private boolean validate(FileConfiguration configToValidate) {
        List<String> warnings = new ArrayList<>();

        int interval = configToValidate.getInt("check-interval", 10);
        if (interval <= 0) {
            warnings.add("check-interval must be > 0, defaulting to 10");
        }

        int delay = configToValidate.getInt("transition-delay", 0);
        if (delay < 0) {
            warnings.add("transition-delay must be >= 0, defaulting to 0");
        }

        String npDisplay = configToValidate.getString("now-playing-display", "chat");
        if (!isValidDisplayMode(npDisplay)) {
            warnings.add("Invalid now-playing-display '" + npDisplay + "', defaulting to chat");
        }

        String msDisplay = configToValidate.getString("music-stopped-display", "none");
        if (!isValidDisplayMode(msDisplay)) {
            warnings.add("Invalid music-stopped-display '" + msDisplay + "', defaulting to none");
        }

        // Validate regions
        ConfigurationSection regionsSection = configToValidate.getConfigurationSection("regions");
        if (regionsSection != null) {
            for (String worldName : regionsSection.getKeys(false)) {
                ConfigurationSection worldSection = regionsSection.getConfigurationSection(worldName);
                if (worldSection == null) continue;

                for (String regionId : worldSection.getKeys(false)) {
                    ConfigurationSection regionSection = worldSection.getConfigurationSection(regionId);
                    if (regionSection == null) continue;

                    validateRegionSection(regionId, worldName, regionSection, warnings);
                }
            }
        }

        if (!warnings.isEmpty()) {
            logger.warning("=== Configuration Warnings ===");
            warnings.forEach(w -> logger.warning("  - " + w));
            logger.warning("==============================");
        }

        // All config values have fallback defaults, so no conditions are currently fatal.
        // The boolean return type is preserved for future use (e.g. breaking schema changes).
        return true;
    }

    private void validateRegionSection(String regionId, String worldName, ConfigurationSection section, List<String> warnings) {
        String prefix = "Region " + regionId + " in " + worldName;

        // Check for sound(s) - either single sound or sounds list
        boolean hasSingleSound = section.contains("sound");
        boolean hasSoundsList = section.contains("sounds");

        if (!hasSingleSound && !hasSoundsList) {
            warnings.add(prefix + ": No sound or sounds specified");
            return;
        }

        if (hasSingleSound) {
            validateSoundKey(section.getString("sound"), prefix, warnings);
        }

        if (hasSoundsList) {
            var soundsList = section.getMapList("sounds");
            if (soundsList.isEmpty()) {
                warnings.add(prefix + ": sounds list is empty");
            }
            for (int i = 0; i < soundsList.size(); i++) {
                var entry = soundsList.get(i);
                Object soundObj = entry.get("sound");
                if (soundObj == null) {
                    warnings.add(prefix + ": sounds[" + i + "] has no sound key");
                } else {
                    validateSoundKey(soundObj.toString(), prefix + " sounds[" + i + "]", warnings);
                }
                Object durationObj = entry.get("duration");
                if (durationObj == null) {
                    warnings.add(prefix + ": sounds[" + i + "] has no duration");
                }
            }
        }

        if (!section.contains("volume")) {
            warnings.add(prefix + ": No volume specified");
        }

        if (!section.contains("loop")) {
            warnings.add(prefix + ": No loop specified");
        }

        boolean loop = section.getBoolean("loop", false);
        if (hasSingleSound && !hasSoundsList && loop) {
            double duration = section.getDouble("duration", 0);
            if (duration <= 0) {
                warnings.add(prefix + ": has loop=true but no valid duration");
            }
        }

        String category = section.getString("category", "MUSIC");
        try {
            Sound.Source.valueOf(category.toUpperCase());
        } catch (IllegalArgumentException e) {
            warnings.add(prefix + ": Invalid sound category '" + category + "', will use MUSIC");
        }

        String order = section.getString("order", "sequential");
        if (!order.equalsIgnoreCase("sequential") && !order.equalsIgnoreCase("shuffle")) {
            warnings.add(prefix + ": Invalid order '" + order + "', will use sequential");
        }

        // Validate variants
        ConfigurationSection variantsSection = section.getConfigurationSection("variants");
        if (variantsSection != null) {
            for (String variantName : variantsSection.getKeys(false)) {
                try {
                    VariantType.valueOf(variantName.toUpperCase());
                } catch (IllegalArgumentException e) {
                    warnings.add(prefix + ": Unknown variant type '" + variantName + "'");
                    continue;
                }

                ConfigurationSection variantSection = variantsSection.getConfigurationSection(variantName);
                if (variantSection != null) {
                    boolean variantHasSound = variantSection.contains("sound");
                    boolean variantHasSounds = variantSection.contains("sounds");
                    if (!variantHasSound && !variantHasSounds) {
                        warnings.add(prefix + " variant " + variantName + ": No sound or sounds specified");
                    }
                    if (variantHasSound) {
                        validateSoundKey(variantSection.getString("sound"), prefix + " variant " + variantName, warnings);
                    }
                }
            }
        }
    }

    private void validateSoundKey(String sound, String context, List<String> warnings) {
        if (sound == null || sound.isEmpty()) {
            warnings.add(context + ": Empty sound key");
            return;
        }
        try {
            Key.key(sound);
        } catch (Exception e) {
            warnings.add(context + ": Invalid sound key '" + sound + "'");
        }
    }

    private boolean isValidDisplayMode(String mode) {
        return mode != null && (mode.equals("chat") || mode.equals("actionbar") || mode.equals("title") || mode.equals("none"));
    }

    private void cacheValues() {
        debug = config.getBoolean("debug", false);

        checkInterval = config.getInt("check-interval", 10);
        if (checkInterval <= 0) checkInterval = 10;

        eventPlayerJoin = config.getBoolean("events.player-join", true);
        eventPlayerTeleport = config.getBoolean("events.player-teleport", true);
        eventPlayerRespawn = config.getBoolean("events.player-respawn", true);
        eventPlayerChangeWorld = config.getBoolean("events.player-change-world", true);

        transitionDelay = Math.max(0, config.getInt("transition-delay", 0));
        stopVanillaMusic = config.getBoolean("stop-vanilla-music", false);
        nowPlayingDisplay = config.getString("now-playing-display", "chat");
        if (!isValidDisplayMode(nowPlayingDisplay)) nowPlayingDisplay = "chat";
        musicStoppedDisplay = config.getString("music-stopped-display", "none");
        if (!isValidDisplayMode(musicStoppedDisplay)) musicStoppedDisplay = "none";

        regionData = loadRegionData();
    }

    private Map<String, Map<String, RegionConfig>> loadRegionData() {
        Map<String, Map<String, RegionConfig>> result = new HashMap<>();
        ConfigurationSection regionsSection = config.getConfigurationSection("regions");
        if (regionsSection == null) return result;

        for (String worldName : regionsSection.getKeys(false)) {
            ConfigurationSection worldSection = regionsSection.getConfigurationSection(worldName);
            if (worldSection == null) continue;

            Map<String, RegionConfig> worldRegions = new HashMap<>();
            for (String regionId : worldSection.getKeys(false)) {
                RegionConfig data = parseRegionConfig(worldName, regionId, worldSection.getConfigurationSection(regionId));
                if (data != null) {
                    worldRegions.put(regionId, data);
                }
            }

            if (!worldRegions.isEmpty()) {
                result.put(worldName, worldRegions);
            }
        }

        return result;
    }

    private RegionConfig parseRegionConfig(String worldName, String regionId, ConfigurationSection section) {
        if (section == null) return null;

        // Parse tracks (single sound or sounds list)
        List<RegionTrack> tracks = parseTracks(section);
        if (tracks.isEmpty()) return null;

        if (!section.contains("volume") || !section.contains("loop")) return null;

        float volume = (float) section.getDouble("volume", 1.0);
        boolean loop = section.getBoolean("loop", false);

        String categoryStr = section.getString("category", "MUSIC");
        Sound.Source soundSource;
        try {
            soundSource = Sound.Source.valueOf(categoryStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            soundSource = Sound.Source.MUSIC;
        }

        int configPriority = section.contains("priority")
                ? section.getInt("priority")
                : RegionConfig.USE_WORLDGUARD_PRIORITY;

        String orderStr = section.getString("order", "sequential");
        PlaybackOrder order = orderStr.equalsIgnoreCase("shuffle")
                ? PlaybackOrder.SHUFFLE
                : PlaybackOrder.SEQUENTIAL;

        // Parse variants
        Map<VariantType, List<RegionTrack>> variants = parseVariants(section, loop);

        return new RegionConfig(regionId, worldName, volume, loop, soundSource,
                configPriority, order, tracks, variants);
    }

    private List<RegionTrack> parseTracks(ConfigurationSection section) {
        List<RegionTrack> tracks = new ArrayList<>();

        // Check for sounds list first (playlist format)
        if (section.contains("sounds")) {
            var soundsList = section.getMapList("sounds");
            for (var entry : soundsList) {
                RegionTrack track = parseTrackFromMap(entry);
                if (track != null) {
                    tracks.add(track);
                }
            }
        }

        // Fall back to single sound
        if (tracks.isEmpty()) {
            String soundStr = section.getString("sound");
            if (soundStr == null || soundStr.isEmpty()) return tracks;

            Key soundKey;
            try {
                soundKey = Key.key(soundStr);
            } catch (Exception e) {
                return tracks;
            }

            double durationSeconds = section.getDouble("duration", 0);
            long durationTicks = (long) (durationSeconds * 20);
            boolean loop = section.getBoolean("loop", false);

            // Non-looping single track: duration 0 is fine (plays once)
            if (loop && durationTicks <= 0) return tracks;

            String name = section.getString("name");
            tracks.add(new RegionTrack(soundKey, durationTicks, name));
        }

        return tracks;
    }

    private RegionTrack parseTrackFromMap(Map<?, ?> entry) {
        Object soundObj = entry.get("sound");
        if (soundObj == null) return null;

        Key soundKey;
        try {
            soundKey = Key.key(soundObj.toString());
        } catch (Exception e) {
            return null;
        }

        Object durationObj = entry.get("duration");
        if (durationObj == null) return null;

        double durationSeconds;
        if (durationObj instanceof Number num) {
            durationSeconds = num.doubleValue();
        } else {
            try {
                durationSeconds = Double.parseDouble(durationObj.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        long durationTicks = (long) (durationSeconds * 20);
        if (durationTicks <= 0) return null;

        Object nameObj = entry.get("name");
        String name = nameObj != null ? nameObj.toString() : null;
        return new RegionTrack(soundKey, durationTicks, name);
    }

    private Map<VariantType, List<RegionTrack>> parseVariants(ConfigurationSection section, boolean parentLoop) {
        Map<VariantType, List<RegionTrack>> variants = new HashMap<>();

        ConfigurationSection variantsSection = section.getConfigurationSection("variants");
        if (variantsSection == null) return variants;

        for (String variantName : variantsSection.getKeys(false)) {
            VariantType type;
            try {
                type = VariantType.valueOf(variantName.toUpperCase());
            } catch (IllegalArgumentException e) {
                continue;
            }

            ConfigurationSection variantSection = variantsSection.getConfigurationSection(variantName);
            if (variantSection == null) continue;

            List<RegionTrack> variantTracks = parseVariantTracks(variantSection, parentLoop);
            if (!variantTracks.isEmpty()) {
                variants.put(type, variantTracks);
            }
        }

        return variants;
    }

    private List<RegionTrack> parseVariantTracks(ConfigurationSection section, boolean parentLoop) {
        List<RegionTrack> tracks = new ArrayList<>();

        // Check for sounds list first
        if (section.contains("sounds")) {
            var soundsList = section.getMapList("sounds");
            for (var entry : soundsList) {
                RegionTrack track = parseTrackFromMap(entry);
                if (track != null) {
                    tracks.add(track);
                }
            }
        }

        // Fall back to single sound
        if (tracks.isEmpty()) {
            String soundStr = section.getString("sound");
            if (soundStr == null || soundStr.isEmpty()) return tracks;

            Key soundKey;
            try {
                soundKey = Key.key(soundStr);
            } catch (Exception e) {
                return tracks;
            }

            double durationSeconds = section.getDouble("duration", 0);
            long durationTicks = (long) (durationSeconds * 20);

            if (parentLoop && durationTicks <= 0) return tracks;

            String name = section.getString("name");
            tracks.add(new RegionTrack(soundKey, durationTicks, name));
        }

        return tracks;
    }

    public int countConfiguredRegions() {
        int count = 0;
        for (Map<String, RegionConfig> worldRegions : regionData.values()) {
            count += worldRegions.size();
        }
        return count;
    }

    // --- Getters ---

    public FileConfiguration getConfig() {
        return config;
    }

    public Map<String, Map<String, RegionConfig>> getRegionData() {
        return regionData;
    }

    public boolean isDebug() {
        return debug;
    }

    public int getCheckInterval() {
        return checkInterval;
    }

    public boolean isEventPlayerJoin() {
        return eventPlayerJoin;
    }

    public boolean isEventPlayerTeleport() {
        return eventPlayerTeleport;
    }

    public boolean isEventPlayerRespawn() {
        return eventPlayerRespawn;
    }

    public boolean isEventPlayerChangeWorld() {
        return eventPlayerChangeWorld;
    }

    public int getTransitionDelay() {
        return transitionDelay;
    }

    public boolean isStopVanillaMusic() {
        return stopVanillaMusic;
    }

    public String getNowPlayingDisplay() {
        return nowPlayingDisplay;
    }

    public String getMusicStoppedDisplay() {
        return musicStoppedDisplay;
    }
}
