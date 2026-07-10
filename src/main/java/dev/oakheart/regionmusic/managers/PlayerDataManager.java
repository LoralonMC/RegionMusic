package dev.oakheart.regionmusic.managers;

import dev.oakheart.regionmusic.RegionMusic;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class PlayerDataManager {

    private static final int DEFAULT_VOLUME = 100;
    private static final int MIN_VOLUME = 0;
    private static final int MAX_VOLUME = 100;

    /** How often the dirty-flag flush timer runs (ticks). 60s default. */
    private static final long FLUSH_INTERVAL_TICKS = 20L * 60L;

    private final RegionMusic plugin;
    private final Path dataFile;
    private final NamespacedKey discoveryKey;

    private final Set<UUID> disabledPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> playerVolumes = new ConcurrentHashMap<>();

    // In-memory cache of each online player's discovered-region keys.
    // Populated on demand from the PDC (which remains the source of truth).
    private final Map<UUID, Set<String>> discoveredCache = new ConcurrentHashMap<>();

    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private BukkitTask flushTask;

    public PlayerDataManager(RegionMusic plugin) {
        this.plugin = plugin;
        this.dataFile = plugin.getDataFolder().toPath().resolve("player-data.yml");
        this.discoveryKey = new NamespacedKey(plugin, "discovered-regions");
        load();
        startFlushTimer();
    }

    // --- Toggle ---

    public boolean isMusicEnabled(UUID playerId) {
        return !disabledPlayers.contains(playerId);
    }

    public boolean toggle(UUID playerId) {
        if (disabledPlayers.remove(playerId)) {
            markDirty();
            return true;
        }
        disabledPlayers.add(playerId);
        markDirty();
        return false;
    }

    public void enable(UUID playerId) {
        if (disabledPlayers.remove(playerId)) {
            markDirty();
        }
    }

    public void disable(UUID playerId) {
        if (disabledPlayers.add(playerId)) {
            markDirty();
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
        Integer previous;
        if (percent == DEFAULT_VOLUME) {
            previous = playerVolumes.remove(playerId);
        } else {
            previous = playerVolumes.put(playerId, percent);
        }
        if (previous == null || previous != percent) {
            markDirty();
        }
    }

    // --- Discovery (PDC-based, with in-memory cache) ---

    /**
     * Marks a region as discovered for this player. Returns true the first time
     * this region is seen (newly discovered), false if it was already known.
     */
    public boolean discoverRegion(Player player, String worldName, String regionId) {
        String regionKey = worldName + ":" + regionId;
        Set<String> discovered = ensureCached(player);

        if (!discovered.add(regionKey)) {
            return false;
        }

        writeDiscovered(player, discovered);
        return true;
    }

    public boolean hasDiscoveredRegion(Player player, String worldName, String regionId) {
        return ensureCached(player).contains(worldName + ":" + regionId);
    }

    /** Pre-populate the discovery cache for a player (called from join). */
    public void preloadDiscovered(Player player) {
        ensureCached(player);
    }

    /** Drop the cached set for a player (called from quit). */
    public void unloadDiscovered(UUID playerId) {
        discoveredCache.remove(playerId);
    }

    /**
     * Look up the cached discovered set, populating from PDC on first access.
     * Also migrates any legacy comma-delimited STRING entries to the modern
     * LIST type on first encounter.
     */
    private Set<String> ensureCached(Player player) {
        UUID id = player.getUniqueId();
        Set<String> cached = discoveredCache.get(id);
        if (cached != null) return cached;

        Set<String> loaded = readFromPdc(player);
        // putIfAbsent guards against a race where two threads load simultaneously
        Set<String> winner = discoveredCache.putIfAbsent(id, loaded);
        return winner != null ? winner : loaded;
    }

    private Set<String> readFromPdc(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();

        // Paper's PDC throws IllegalArgumentException when get() is called with a
        // type that doesn't match the stored tag, so probe with has() first rather
        // than relying on a null return.
        if (pdc.has(discoveryKey, PersistentDataType.LIST.strings())) {
            List<String> list = pdc.get(discoveryKey, PersistentDataType.LIST.strings());
            if (list != null) {
                return new LinkedHashSet<>(list);
            }
        }

        // Legacy fallback: comma-delimited STRING from pre-1.1 RegionMusic.
        // Migrate in place so subsequent reads hit the LIST branch above.
        if (pdc.has(discoveryKey, PersistentDataType.STRING)) {
            String legacy = pdc.get(discoveryKey, PersistentDataType.STRING);
            if (legacy != null && !legacy.isEmpty()) {
                Set<String> migrated = new LinkedHashSet<>();
                for (String entry : legacy.split(",")) {
                    if (!entry.isEmpty()) migrated.add(entry);
                }
                pdc.remove(discoveryKey);
                pdc.set(discoveryKey, PersistentDataType.LIST.strings(), new ArrayList<>(migrated));
                return migrated;
            }
            // Empty legacy string — just drop it
            pdc.remove(discoveryKey);
        }

        return new HashSet<>();
    }

    private void writeDiscovered(Player player, Set<String> discovered) {
        player.getPersistentDataContainer().set(
                discoveryKey, PersistentDataType.LIST.strings(), new ArrayList<>(discovered));
    }

    // --- Persistence ---

    private void markDirty() {
        dirty.set(true);
    }

    private void startFlushTimer() {
        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (dirty.compareAndSet(true, false)) {
                try {
                    writeToDisk();
                } catch (IOException e) {
                    dirty.set(true); // retry next tick
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to save player-data.yml (will retry)", e);
                }
            }
        }, FLUSH_INTERVAL_TICKS, FLUSH_INTERVAL_TICKS);
    }

    private void load() {
        if (!Files.exists(dataFile)) {
            plugin.debug("player-data.yml not present — starting fresh");
            return;
        }

        dev.oakheart.config.ConfigManager config;
        try {
            config = dev.oakheart.config.ConfigManager.load(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to load player-data.yml — starting with empty state", e);
            return;
        }

        disabledPlayers.clear();
        playerVolumes.clear();

        for (String uuidStr : config.getStringList("disabled-players")) {
            try {
                disabledPlayers.add(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid UUID in player-data.yml disabled-players: " + uuidStr);
            }
        }

        dev.oakheart.config.ConfigManager volumes = config.getSection("volumes");
        if (volumes != null) {
            for (String uuidStr : volumes.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    int vol = volumes.getInt(uuidStr, DEFAULT_VOLUME);
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

    /**
     * Build a fresh config document from current in-memory state and write it to disk.
     * Always called from the flush task (async) or saveSync() (main on disable).
     */
    private void writeToDisk() throws IOException {
        // Snapshot under the live maps' iterators — both are concurrent.
        List<String> disabledCopy = disabledPlayers.stream()
                .map(UUID::toString)
                .sorted()
                .toList();
        Map<UUID, Integer> volumesCopy = Map.copyOf(playerVolumes);

        if (!Files.exists(dataFile.getParent())) {
            Files.createDirectories(dataFile.getParent());
        }

        dev.oakheart.config.ConfigManager config = dev.oakheart.config.ConfigManager.fromString("");
        config.set("disabled-players", disabledCopy);

        // Volumes are written under a "volumes" section. Sort by UUID for stable output.
        List<Map.Entry<UUID, Integer>> sortedVolumes = new ArrayList<>(volumesCopy.entrySet());
        sortedVolumes.sort((a, b) -> a.getKey().compareTo(b.getKey()));
        for (Map.Entry<UUID, Integer> entry : sortedVolumes) {
            config.set("volumes." + entry.getKey(), entry.getValue());
        }

        config.save(dataFile);
    }

    /**
     * Synchronous flush — called from onDisable. Cancels the background timer
     * and writes any pending changes immediately.
     */
    public void saveSync() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        if (!dirty.compareAndSet(true, false)) return;
        try {
            writeToDisk();
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to flush player-data.yml on disable", e);
        }
    }

    public void reload() {
        load();
    }

    // --- Test/inspection helpers ---

    Set<UUID> getDisabledPlayersView() {
        return Collections.unmodifiableSet(disabledPlayers);
    }

    Map<UUID, Integer> getPlayerVolumesView() {
        return Collections.unmodifiableMap(playerVolumes);
    }
}
