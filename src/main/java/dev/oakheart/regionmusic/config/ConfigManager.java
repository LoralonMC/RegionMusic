package dev.oakheart.regionmusic.config;

import dev.oakheart.regionmusic.RegionConfig;
import dev.oakheart.regionmusic.RegionConfig.PlaybackOrder;
import dev.oakheart.regionmusic.RegionConfig.VariantType;
import dev.oakheart.regionmusic.RegionMusic;
import dev.oakheart.regionmusic.RegionTrack;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ConfigManager {

    private final RegionMusic plugin;
    private final Logger logger;
    private final Path configFile;
    private dev.oakheart.config.ConfigManager config;

    // General settings
    private boolean debug;
    private int checkInterval;
    private boolean eventPlayerJoin;
    private boolean eventPlayerTeleport;
    private boolean eventPlayerRespawn;
    private boolean eventPlayerChangeWorld;

    private int transitionDelay;
    private boolean stopVanillaMusic;

    // Cached region data: world -> region -> RegionConfig
    private Map<String, Map<String, RegionConfig>> regionData = Map.of();

    public ConfigManager(RegionMusic plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configFile = plugin.getDataFolder().toPath().resolve("config.yml");
    }

    public void load() {
        if (!configFile.toFile().exists()) {
            plugin.saveResource("config.yml", false);
        }

        try {
            config = dev.oakheart.config.ConfigManager.load(configFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config.yml", e);
        }

        mergeDefaults();
        validate(config);
        cacheValues();
    }

    public boolean reload() {
        dev.oakheart.config.ConfigManager newConfig;
        try {
            newConfig = dev.oakheart.config.ConfigManager.load(configFile);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to reload config.yml", e);
            return false;
        }

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
        try (var stream = plugin.getResource("config.yml")) {
            if (stream == null) return;
            var defaults = dev.oakheart.config.ConfigManager.fromStream(stream);
            if (config.mergeDefaults(defaults)) {
                config.save();
            }
        } catch (IOException e) {
            logger.warning("Failed to merge config defaults: " + e.getMessage());
        }
    }

    private boolean validate(dev.oakheart.config.ConfigManager configToValidate) {
        List<String> warnings = new ArrayList<>();

        int interval = configToValidate.getInt("check-interval", 10);
        if (interval <= 0) {
            warnings.add("check-interval must be > 0, defaulting to 10");
        }

        int delay = configToValidate.getInt("transition-delay", 0);
        if (delay < 0) {
            warnings.add("transition-delay must be >= 0, defaulting to 0");
        }

        // Validate regions
        dev.oakheart.config.ConfigManager regionsSection = configToValidate.getSection("regions");
        if (regionsSection != null) {
            for (String worldName : regionsSection.getKeys(false)) {
                dev.oakheart.config.ConfigManager worldSection = regionsSection.getSection(worldName);
                if (worldSection == null) continue;

                for (String regionId : worldSection.getKeys(false)) {
                    dev.oakheart.config.ConfigManager regionSection = worldSection.getSection(regionId);
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

        return true;
    }

    private void validateRegionSection(String regionId, String worldName,
                                       dev.oakheart.config.ConfigManager section, List<String> warnings) {
        String prefix = "Region " + regionId + " in " + worldName;

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
        dev.oakheart.config.ConfigManager variantsSection = section.getSection("variants");
        if (variantsSection != null) {
            for (String variantName : variantsSection.getKeys(false)) {
                try {
                    VariantType.valueOf(variantName.toUpperCase());
                } catch (IllegalArgumentException e) {
                    warnings.add(prefix + ": Unknown variant type '" + variantName + "'");
                    continue;
                }

                dev.oakheart.config.ConfigManager variantSection = variantsSection.getSection(variantName);
                if (variantSection != null) {
                    boolean variantHasSound = variantSection.contains("sound");
                    boolean variantHasSounds = variantSection.contains("sounds");
                    if (!variantHasSound && !variantHasSounds) {
                        warnings.add(prefix + " variant " + variantName + ": No sound or sounds specified");
                    }
                    if (variantHasSound) {
                        validateSoundKey(variantSection.getString("sound"),
                                prefix + " variant " + variantName, warnings);
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

        regionData = loadRegionData();
    }

    private Map<String, Map<String, RegionConfig>> loadRegionData() {
        Map<String, Map<String, RegionConfig>> result = new HashMap<>();
        dev.oakheart.config.ConfigManager regionsSection = config.getSection("regions");
        if (regionsSection == null) return result;

        for (String worldName : regionsSection.getKeys(false)) {
            dev.oakheart.config.ConfigManager worldSection = regionsSection.getSection(worldName);
            if (worldSection == null) continue;

            Map<String, RegionConfig> worldRegions = new HashMap<>();
            for (String regionId : worldSection.getKeys(false)) {
                RegionConfig data = parseRegionConfig(worldName, regionId, worldSection.getSection(regionId));
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

    private RegionConfig parseRegionConfig(String worldName, String regionId,
                                           dev.oakheart.config.ConfigManager section) {
        if (section == null) return null;

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

        Map<VariantType, List<RegionTrack>> variants = parseVariants(section, loop);

        return new RegionConfig(regionId, worldName, volume, loop, soundSource,
                configPriority, order, tracks, variants);
    }

    private List<RegionTrack> parseTracks(dev.oakheart.config.ConfigManager section) {
        List<RegionTrack> tracks = new ArrayList<>();

        if (section.contains("sounds")) {
            var soundsList = section.getMapList("sounds");
            for (var entry : soundsList) {
                RegionTrack track = parseTrackFromMap(entry);
                if (track != null) {
                    tracks.add(track);
                }
            }
        }

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

            if (loop && durationTicks <= 0) return tracks;

            String name = section.getString("name");
            tracks.add(new RegionTrack(soundKey, durationTicks, name));
        }

        return tracks;
    }

    private RegionTrack parseTrackFromMap(Map<String, Object> entry) {
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

    private Map<VariantType, List<RegionTrack>> parseVariants(dev.oakheart.config.ConfigManager section,
                                                              boolean parentLoop) {
        Map<VariantType, List<RegionTrack>> variants = new HashMap<>();

        dev.oakheart.config.ConfigManager variantsSection = section.getSection("variants");
        if (variantsSection == null) return variants;

        for (String variantName : variantsSection.getKeys(false)) {
            VariantType type;
            try {
                type = VariantType.valueOf(variantName.toUpperCase());
            } catch (IllegalArgumentException e) {
                continue;
            }

            dev.oakheart.config.ConfigManager variantSection = variantsSection.getSection(variantName);
            if (variantSection == null) continue;

            List<RegionTrack> variantTracks = parseVariantTracks(variantSection, parentLoop);
            if (!variantTracks.isEmpty()) {
                variants.put(type, variantTracks);
            }
        }

        return variants;
    }

    private List<RegionTrack> parseVariantTracks(dev.oakheart.config.ConfigManager section, boolean parentLoop) {
        List<RegionTrack> tracks = new ArrayList<>();

        if (section.contains("sounds")) {
            var soundsList = section.getMapList("sounds");
            for (var entry : soundsList) {
                RegionTrack track = parseTrackFromMap(entry);
                if (track != null) {
                    tracks.add(track);
                }
            }
        }

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

    public dev.oakheart.config.ConfigManager getConfig() {
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
}
