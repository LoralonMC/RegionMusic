package dev.oakheart.regionmusic.listeners;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import dev.oakheart.regionmusic.MusicManager;
import dev.oakheart.regionmusic.RegionConfig;
import dev.oakheart.regionmusic.RegionConfig.VariantType;
import dev.oakheart.regionmusic.RegionMusic;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RegionMusicListener implements Listener {

    private static final long JOIN_CHECK_DELAY_TICKS = 10L;
    private static final long EVENT_CHECK_DELAY_TICKS = 1L;

    // How often to re-send stopSound for vanilla music suppression (in check cycles)
    // With default check-interval of 10 ticks, this means every 50 ticks (~2.5 seconds)
    private static final int VANILLA_STOP_INTERVAL = 5;

    private final RegionMusic plugin;
    private final MusicManager musicManager;

    // Tracks "world:regionId:VARIANT" (or "world:regionId" for default)
    private final Map<UUID, String> playerCurrentRegion = new HashMap<>();
    // Tracks last checked block position to skip stationary players (packed x/y/z/world)
    private final Map<UUID, Long> playerLastPosition = new HashMap<>();
    // Counts check cycles per player for throttling vanilla music stops
    private final Map<UUID, Integer> vanillaStopCounter = new HashMap<>();
    // Tracks pending transition-delay tasks
    private final Map<UUID, BukkitTask> transitionTasks = new HashMap<>();
    // Tracks players that need a forced re-check (e.g. after event or clearPlayerRegion)
    private final Map<UUID, Boolean> forceCheck = new HashMap<>();

    private BukkitTask checkTask;

    public RegionMusicListener(RegionMusic plugin, MusicManager musicManager) {
        this.plugin = plugin;
        this.musicManager = musicManager;
    }

    public void startChecking() {
        int interval = plugin.getConfigManager().getCheckInterval();
        checkTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    checkPlayerRegion(player, false);
                }
            }
        }.runTaskTimer(plugin, 20L, interval);
    }

    public void stopChecking() {
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
    }

    public void refresh() {
        stopChecking();
        cancelAllTransitions();
        playerCurrentRegion.clear();
        playerLastPosition.clear();
        vanillaStopCounter.clear();
        forceCheck.clear();
        startChecking();
    }

    // --- Events ---

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!plugin.getConfigManager().isEventPlayerJoin()) return;
        forceCheck.put(event.getPlayer().getUniqueId(), true);
        Bukkit.getScheduler().runTaskLater(plugin, () ->
                checkPlayerRegion(event.getPlayer(), true), JOIN_CHECK_DELAY_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        playerCurrentRegion.remove(playerId);
        playerLastPosition.remove(playerId);
        vanillaStopCounter.remove(playerId);
        forceCheck.remove(playerId);
        cancelTransition(playerId);
        musicManager.cleanupPlayer(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (!plugin.getConfigManager().isEventPlayerTeleport()) return;
        forceCheck.put(event.getPlayer().getUniqueId(), true);
        Bukkit.getScheduler().runTaskLater(plugin, () ->
                checkPlayerRegion(event.getPlayer(), true), EVENT_CHECK_DELAY_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (!plugin.getConfigManager().isEventPlayerChangeWorld()) return;
        forceCheck.put(event.getPlayer().getUniqueId(), true);
        Bukkit.getScheduler().runTaskLater(plugin, () ->
                checkPlayerRegion(event.getPlayer(), true), EVENT_CHECK_DELAY_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (!plugin.getConfigManager().isEventPlayerRespawn()) return;
        forceCheck.put(event.getPlayer().getUniqueId(), true);
        Bukkit.getScheduler().runTaskLater(plugin, () ->
                checkPlayerRegion(event.getPlayer(), true), EVENT_CHECK_DELAY_TICKS);
    }

    // --- Core logic ---

    private void checkPlayerRegion(Player player, boolean forced) {
        if (!player.isOnline()) return;

        UUID playerId = player.getUniqueId();

        // Consume force flag if set by an event
        if (!forced && forceCheck.remove(playerId) != null) {
            forced = true;
        }

        // Skip if previewing
        if (musicManager.isPreviewing(player)) return;

        // Check if music disabled
        if (!plugin.getPlayerDataManager().isMusicEnabled(playerId)) {
            handleNoMusicRegion(player);
            return;
        }

        // Skip stationary players in the periodic check (not forced by events).
        // Variant checks (weather/time) still need to run, so only skip if player
        // has no variants configured for their current region.
        if (!forced && !hasMovedSinceLastCheck(player)) {
            // Still need to suppress vanilla music periodically
            if (plugin.getConfigManager().isStopVanillaMusic() && playerCurrentRegion.containsKey(playerId)) {
                tickVanillaStop(player);
            }
            // Still check for variant changes even when stationary
            if (playerCurrentRegion.containsKey(playerId)) {
                checkVariantChange(player);
            }
            return;
        }

        World world = player.getWorld();
        String worldName = world.getName();

        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionManager regionManager = container.get(BukkitAdapter.adapt(world));

        if (regionManager == null) {
            handleNoMusicRegion(player);
            return;
        }

        ApplicableRegionSet regions = regionManager.getApplicableRegions(
                BukkitAdapter.asBlockVector(player.getLocation())
        );

        RegionConfig regionConfig = findMusicForRegions(regions, worldName);

        if (regionConfig != null) {
            // Suppress vanilla music periodically while in a music region
            if (plugin.getConfigManager().isStopVanillaMusic()) {
                tickVanillaStop(player);
            }

            VariantType activeVariant = resolveVariant(player, regionConfig);
            String regionKey = buildRegionKey(regionConfig, activeVariant);
            String currentKey = playerCurrentRegion.get(playerId);

            if (!regionKey.equals(currentKey)) {
                boolean isRegionTransition = currentKey != null
                        && !isSameRegionDifferentVariant(currentKey, regionKey);

                // Cancel any pending transition
                cancelTransition(playerId);

                int delay = plugin.getConfigManager().getTransitionDelay();

                // Only apply transition delay for region-to-region transitions
                if (isRegionTransition && delay > 0) {
                    // Stop current music immediately
                    musicManager.stopMusic(player);
                    playerCurrentRegion.remove(playerId);

                    // Schedule delayed start
                    BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        transitionTasks.remove(playerId);
                        if (!player.isOnline()) return;

                        // Re-verify the player is still in the target region
                        RegionConfig verify = findMusicForCurrentLocation(player);
                        if (verify != null && verify.isSameRegion(regionConfig)) {
                            VariantType verifyVariant = resolveVariant(player, verify);
                            startMusicForRegion(player, verify, verifyVariant);
                        }
                    }, delay);
                    transitionTasks.put(playerId, task);
                } else {
                    startMusicForRegion(player, regionConfig, activeVariant);
                }
            }
        } else {
            handleNoMusicRegion(player);
        }
    }

    /**
     * Checks for variant changes without doing a full WorldGuard region lookup.
     * Used for stationary players who might be affected by time/weather changes.
     */
    private void checkVariantChange(Player player) {
        UUID playerId = player.getUniqueId();
        String currentKey = playerCurrentRegion.get(playerId);
        if (currentKey == null) return;

        RegionConfig currentConfig = musicManager.getCurrentRegionConfig(player);
        if (currentConfig == null || currentConfig.variants().isEmpty()) return;

        VariantType activeVariant = resolveVariant(player, currentConfig);
        String newKey = buildRegionKey(currentConfig, activeVariant);

        if (!newKey.equals(currentKey)) {
            playerCurrentRegion.put(playerId, newKey);
            musicManager.playMusic(player, currentConfig, activeVariant);
            plugin.debug(player.getName() + " variant changed to "
                    + (activeVariant != null ? activeVariant : "default")
                    + " in " + currentConfig.regionId());
        }
    }

    private void startMusicForRegion(Player player, RegionConfig regionConfig, VariantType activeVariant) {
        UUID playerId = player.getUniqueId();
        String regionKey = buildRegionKey(regionConfig, activeVariant);

        playerCurrentRegion.put(playerId, regionKey);
        musicManager.playMusic(player, regionConfig, activeVariant);

        // Discovery check
        boolean newDiscovery = plugin.getPlayerDataManager().discoverRegion(
                player, regionConfig.worldName(), regionConfig.regionId());
        if (newDiscovery) {
            plugin.getMessageManager().sendRegionDiscovered(player, regionConfig.regionId(), regionConfig.worldName());
        }

        plugin.debug(player.getName() + " entered music region: " + regionConfig.regionId()
                + " in " + regionConfig.worldName()
                + (activeVariant != null ? " [" + activeVariant + "]" : ""));
    }

    private void handleNoMusicRegion(Player player) {
        UUID playerId = player.getUniqueId();
        cancelTransition(playerId);
        vanillaStopCounter.remove(playerId);

        String previousRegion = playerCurrentRegion.remove(playerId);
        if (previousRegion != null) {
            musicManager.stopMusic(player);
            plugin.debug(player.getName() + " left music region: " + previousRegion);
        }
    }

    // --- Position tracking ---

    /**
     * Checks if a player has moved to a different block since the last check.
     * Updates the stored position. Uses packed long for zero-allocation comparison.
     */
    private boolean hasMovedSinceLastCheck(Player player) {
        Location loc = player.getLocation();
        // Pack block coords + world into a single long for fast comparison
        // x: 26 bits, z: 26 bits, y: 12 bits = 64 bits (world change detected via world hash)
        long packed = ((long) loc.getBlockX() & 0x3FFFFFF)
                | (((long) loc.getBlockZ() & 0x3FFFFFF) << 26)
                | (((long) loc.getBlockY() & 0xFFF) << 52);
        // XOR in world identity to detect world changes
        packed ^= (long) System.identityHashCode(loc.getWorld()) * 0x9E3779B97F4A7C15L;

        Long previous = playerLastPosition.put(player.getUniqueId(), packed);
        return previous == null || previous != packed;
    }

    // --- Vanilla music suppression ---

    /**
     * Throttled vanilla music stop — only sends the packet every N check cycles.
     */
    private void tickVanillaStop(Player player) {
        UUID playerId = player.getUniqueId();
        int count = vanillaStopCounter.merge(playerId, 1, Integer::sum);
        if (count >= VANILLA_STOP_INTERVAL) {
            vanillaStopCounter.put(playerId, 0);
            player.stopSound(SoundStop.source(Sound.Source.MUSIC));
        }
    }

    // --- Variant resolution ---

    private VariantType resolveVariant(Player player, RegionConfig config) {
        if (config.variants().isEmpty()) return null;

        World world = player.getWorld();

        // Priority: thunder > rain > night
        if (world.isThundering() && config.variants().containsKey(VariantType.THUNDER)) {
            return VariantType.THUNDER;
        }
        if (world.hasStorm() && config.variants().containsKey(VariantType.RAIN)) {
            return VariantType.RAIN;
        }

        long time = world.getTime();
        if (time >= 13000 && time < 23000 && config.variants().containsKey(VariantType.NIGHT)) {
            return VariantType.NIGHT;
        }

        return null;
    }

    // --- Region key helpers ---

    private String buildRegionKey(RegionConfig config, VariantType variant) {
        String key = config.worldName() + ":" + config.regionId();
        if (variant != null) {
            key += ":" + variant.name();
        }
        return key;
    }

    private boolean isSameRegionDifferentVariant(String key1, String key2) {
        String base1 = extractBaseRegionKey(key1);
        String base2 = extractBaseRegionKey(key2);
        return base1.equals(base2);
    }

    private String extractBaseRegionKey(String key) {
        // Format: "world:regionId" or "world:regionId:VARIANT"
        int firstColon = key.indexOf(':');
        if (firstColon < 0) return key;
        int secondColon = key.indexOf(':', firstColon + 1);
        if (secondColon < 0) return key;
        return key.substring(0, secondColon);
    }

    // --- Region lookup helpers ---

    private RegionConfig findMusicForRegions(ApplicableRegionSet regions, String worldName) {
        Map<String, RegionConfig> worldRegions = plugin.getConfigManager().getRegionData().get(worldName);
        if (worldRegions == null) return null;

        RegionConfig highestPriorityMusic = null;
        int highestPriority = Integer.MIN_VALUE;

        for (ProtectedRegion region : regions) {
            RegionConfig data = worldRegions.get(region.getId());
            if (data != null) {
                int priority = data.configPriority() == RegionConfig.USE_WORLDGUARD_PRIORITY
                        ? region.getPriority()
                        : data.configPriority();

                if (priority > highestPriority) {
                    highestPriority = priority;
                    highestPriorityMusic = data;
                }
            }
        }

        return highestPriorityMusic;
    }

    private RegionConfig findMusicForCurrentLocation(Player player) {
        World world = player.getWorld();
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionManager regionManager = container.get(BukkitAdapter.adapt(world));
        if (regionManager == null) return null;

        ApplicableRegionSet regions = regionManager.getApplicableRegions(
                BukkitAdapter.asBlockVector(player.getLocation())
        );

        return findMusicForRegions(regions, world.getName());
    }

    // --- Transition management ---

    private void cancelTransition(UUID playerId) {
        BukkitTask task = transitionTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    private void cancelAllTransitions() {
        transitionTasks.values().forEach(BukkitTask::cancel);
        transitionTasks.clear();
    }

    // --- Cleanup ---

    public void cleanup() {
        stopChecking();
        cancelAllTransitions();
        playerCurrentRegion.clear();
        playerLastPosition.clear();
        vanillaStopCounter.clear();
        forceCheck.clear();
    }

    public void clearPlayerRegion(UUID playerId) {
        playerCurrentRegion.remove(playerId);
        playerLastPosition.remove(playerId);
        vanillaStopCounter.remove(playerId);
        forceCheck.put(playerId, true);
        cancelTransition(playerId);
    }
}
