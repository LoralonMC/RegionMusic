package dev.oakheart.regionmusic;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerDataManager {

    private static final int DEFAULT_VOLUME = 100;
    private static final int MIN_VOLUME = 0;
    private static final int MAX_VOLUME = 100;

    private final RegionMusic plugin;
    private final File dataFile;
    private final NamespacedKey discoveryKey;

    private final Set<UUID> disabledPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> playerVolumes = new ConcurrentHashMap<>();

    public PlayerDataManager(RegionMusic plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "player-data.yml");
        this.discoveryKey = new NamespacedKey(plugin, "discovered-regions");
        load();
    }

    // --- Toggle ---

    public boolean isMusicEnabled(UUID playerId) {
        return !disabledPlayers.contains(playerId);
    }

    public boolean toggle(UUID playerId) {
        if (disabledPlayers.contains(playerId)) {
            disabledPlayers.remove(playerId);
            save();
            return true; // Music is now enabled
        } else {
            disabledPlayers.add(playerId);
            save();
            return false; // Music is now disabled
        }
    }

    public void enable(UUID playerId) {
        if (disabledPlayers.remove(playerId)) {
            save();
        }
    }

    public void disable(UUID playerId) {
        if (disabledPlayers.add(playerId)) {
            save();
        }
    }

    // --- Volume ---

    public int getVolumePercent(UUID playerId) {
        return playerVolumes.getOrDefault(playerId, DEFAULT_VOLUME);
    }

    public float getEffectiveVolume(UUID playerId) {
        return getVolumePercent(playerId) / 100.0f;
    }

    public void setVolumePercent(UUID playerId, int percent) {
        percent = Math.clamp(percent, MIN_VOLUME, MAX_VOLUME);
        if (percent == DEFAULT_VOLUME) {
            playerVolumes.remove(playerId);
        } else {
            playerVolumes.put(playerId, percent);
        }
        save();
    }

    // --- Discovery (PDC-based) ---

    /**
     * Checks if a player has discovered a region, and marks it as discovered if not.
     * @return true if this is the first time (newly discovered), false if already known
     */
    public boolean discoverRegion(Player player, String worldName, String regionId) {
        String regionKey = worldName + ":" + regionId;
        PersistentDataContainer pdc = player.getPersistentDataContainer();

        String existing = pdc.getOrDefault(discoveryKey, PersistentDataType.STRING, "");

        // Check if already discovered
        if (!existing.isEmpty()) {
            for (String entry : existing.split(",")) {
                if (entry.equals(regionKey)) {
                    return false;
                }
            }
        }

        // Add new discovery
        String updated = existing.isEmpty() ? regionKey : existing + "," + regionKey;
        pdc.set(discoveryKey, PersistentDataType.STRING, updated);
        return true;
    }

    public boolean hasDiscoveredRegion(Player player, String worldName, String regionId) {
        String regionKey = worldName + ":" + regionId;
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        String existing = pdc.getOrDefault(discoveryKey, PersistentDataType.STRING, "");

        if (existing.isEmpty()) return false;

        for (String entry : existing.split(",")) {
            if (entry.equals(regionKey)) {
                return true;
            }
        }
        return false;
    }

    // --- Persistence ---

    private void load() {
        if (!dataFile.exists()) {
            return;
        }

        FileConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        disabledPlayers.clear();
        playerVolumes.clear();

        // Load disabled players
        for (String uuidStr : data.getStringList("disabled-players")) {
            try {
                disabledPlayers.add(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid UUID in player-data.yml disabled-players: " + uuidStr);
            }
        }

        // Load volumes
        var volumeSection = data.getConfigurationSection("volumes");
        if (volumeSection != null) {
            for (String uuidStr : volumeSection.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    int vol = volumeSection.getInt(uuidStr, DEFAULT_VOLUME);
                    if (vol != DEFAULT_VOLUME) {
                        playerVolumes.put(uuid, Math.clamp(vol, MIN_VOLUME, MAX_VOLUME));
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid UUID in player-data.yml volumes: " + uuidStr);
                }
            }
        }

        plugin.debug("Loaded " + disabledPlayers.size() + " players with music disabled, "
                + playerVolumes.size() + " with custom volumes");
    }

    private void save() {
        var disabledCopy = disabledPlayers.stream().map(UUID::toString).toList();
        var volumesCopy = Map.copyOf(playerVolumes);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!dataFile.getParentFile().exists()) {
                dataFile.getParentFile().mkdirs();
            }

            FileConfiguration data = new YamlConfiguration();
            data.set("disabled-players", disabledCopy);

            if (!volumesCopy.isEmpty()) {
                for (var entry : volumesCopy.entrySet()) {
                    data.set("volumes." + entry.getKey().toString(), entry.getValue());
                }
            }

            try {
                data.save(dataFile);
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to save player-data.yml: " + e.getMessage());
            }
        });
    }

    public void saveSync() {
        if (!dataFile.getParentFile().exists()) {
            dataFile.getParentFile().mkdirs();
        }

        FileConfiguration data = new YamlConfiguration();
        data.set("disabled-players", disabledPlayers.stream().map(UUID::toString).toList());

        for (var entry : playerVolumes.entrySet()) {
            data.set("volumes." + entry.getKey().toString(), entry.getValue());
        }

        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save player-data.yml: " + e.getMessage());
        }
    }

    public void reload() {
        load();
    }
}
