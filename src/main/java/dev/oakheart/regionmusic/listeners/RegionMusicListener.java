package dev.oakheart.regionmusic.listeners;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import dev.oakheart.regionmusic.RegionMusic;
import dev.oakheart.regionmusic.managers.MusicManager;
import dev.oakheart.regionmusic.model.RegionConfig;
import dev.oakheart.regionmusic.model.RegionConfig.VariantType;
import dev.oakheart.regionmusic.model.RegionKey;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Region transitions are detected via PlayerMoveEvent (with a block-changed
 * guard) and the various forced events (join, teleport, respawn, world change).
 * A slower periodic timer handles variant re-evaluation (weather/time
 * transitions affect stationary players) and the throttled vanilla-music stop.
 */
public class RegionMusicListener implements Listener {

    private static final long JOIN_CHECK_DELAY_TICKS = 10L;
    private static final long EVENT_CHECK_DELAY_TICKS = 1L;

    /**
     * Vanilla-music stop interval, in periodic-timer cycles. At the default
     * check-interval of 10 ticks, this fires every ~2.5s.
     * Only relevant when the region's track uses a non-MUSIC source — see
     * {@link #tickVanillaStop}.
     */
    private static final int VANILLA_STOP_INTERVAL = 5;

    private final RegionMusic plugin;
    private final MusicManager musicManager;

    private final Map<UUID, RegionKey> playerCurrentRegion = new HashMap<>();
    private final Map<UUID, Integer> vanillaStopCounter = new HashMap<>();
    private final Map<UUID, BukkitTask> transitionTasks = new HashMap<>();
    // In-memory variant override for /regionmusic test (admin-only); not persisted.
    private final Map<UUID, VariantType> variantOverrides = new HashMap<>();

    private BukkitTask variantTask;

    public RegionMusicListener(RegionMusic plugin, MusicManager musicManager) {
        this.plugin = plugin;
        this.musicManager = musicManager;
    }

    public void startChecking() {
        int interval = plugin.getConfigManager().getCheckInterval();
        variantTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    tickPlayer(player);
                }
            }
        }.runTaskTimer(plugin, 20L, interval);
    }

    public void stopChecking() {
        if (variantTask != null) {
            variantTask.cancel();
            variantTask = null;
        }
    }

    public void refresh() {
        stopChecking();
        cancelAllTransitions();
        playerCurrentRegion.clear();
        vanillaStopCounter.clear();
        startChecking();
    }

    // --- Events ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Cheap guard: only run the region check when the player has actually
        // moved to a different block. PlayerMoveEvent fires for sub-block
        // movement (head-turns alone don't fire it; small position deltas do).
        if (!event.hasChangedBlock()) return;
        checkPlayerRegion(event.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        plugin.getPlayerDataManager().preloadDiscovered(event.getPlayer());
        if (!plugin.getConfigManager().isEventPlayerJoin()) return;
        scheduleForcedCheck(event.getPlayer(), JOIN_CHECK_DELAY_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        playerCurrentRegion.remove(playerId);
        vanillaStopCounter.remove(playerId);
        variantOverrides.remove(playerId);
        cancelTransition(playerId);
        musicManager.cleanupPlayer(player);
        plugin.getPlayerDataManager().unloadDiscovered(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (!plugin.getConfigManager().isEventPlayerTeleport()) return;
        scheduleForcedCheck(event.getPlayer(), EVENT_CHECK_DELAY_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (!plugin.getConfigManager().isEventPlayerChangeWorld()) return;
        scheduleForcedCheck(event.getPlayer(), EVENT_CHECK_DELAY_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (!plugin.getConfigManager().isEventPlayerRespawn()) return;
        scheduleForcedCheck(event.getPlayer(), EVENT_CHECK_DELAY_TICKS);
    }

    public void scheduleForcedCheck(Player player, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> checkPlayerRegion(player, true), delayTicks);
    }

    // --- Periodic tick (variant re-eval + vanilla-music suppression) ---

    /**
     * Runs every check-interval ticks for each online player. Cheap: no
     * WorldGuard lookup, just re-evaluates variants for players already in a
     * region and ticks the throttled vanilla-music stop counter.
     */
    private void tickPlayer(Player player) {
        if (!player.isOnline()) return;

        if (musicManager.isPreviewing(player)) return;

        UUID playerId = player.getUniqueId();
        if (!plugin.getPlayerDataManager().isMusicEnabled(playerId)) return;

        // PlayerMoveEvent doesn't fire while riding (boats, horses, minecarts),
        // so mounted players never trigger region transitions — music keeps
        // looping after they've left, and never starts when they ride in. Fall
        // back to a full region check on the periodic tick for riders only.
        if (player.isInsideVehicle()) {
            checkPlayerRegion(player, true);
        }

        if (!playerCurrentRegion.containsKey(playerId)) return;

        if (plugin.getConfigManager().isStopVanillaMusic()) {
            tickVanillaStop(player);
        }

        checkVariantChange(player);
    }

    // --- Core region check (called from PlayerMoveEvent + forced events) ---

    private void checkPlayerRegion(Player player, boolean forced) {
        if (!player.isOnline()) return;

        UUID playerId = player.getUniqueId();

        if (musicManager.isPreviewing(player)) return;

        if (!plugin.getPlayerDataManager().isMusicEnabled(playerId)) {
            handleNoMusicRegion(player);
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

        RegionConfig regionConfig = findMusicForRegions(regions, regionManager, worldName);

        if (regionConfig != null) {
            VariantType activeVariant = resolveVariant(player, regionConfig);
            RegionKey newKey = RegionKey.of(regionConfig, activeVariant);
            RegionKey currentKey = playerCurrentRegion.get(playerId);

            if (!newKey.equals(currentKey)) {
                boolean isRegionTransition = currentKey != null && !currentKey.isSameRegion(newKey);

                cancelTransition(playerId);

                int delay = plugin.getConfigManager().getTransitionDelay();

                if (isRegionTransition && delay > 0) {
                    musicManager.stopMusic(player);
                    playerCurrentRegion.remove(playerId);

                    BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        transitionTasks.remove(playerId);
                        if (!player.isOnline()) return;

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

    /** Re-check variants without doing a full WorldGuard region lookup. */
    private void checkVariantChange(Player player) {
        UUID playerId = player.getUniqueId();
        RegionKey currentKey = playerCurrentRegion.get(playerId);
        if (currentKey == null) return;

        RegionConfig currentConfig = musicManager.getCurrentRegionConfig(player);
        if (currentConfig == null) return;
        // No variants AND no admin override → nothing can change
        if (currentConfig.variants().isEmpty() && variantOverrides.get(playerId) == null) return;

        VariantType activeVariant = resolveVariant(player, currentConfig);
        RegionKey newKey = RegionKey.of(currentConfig, activeVariant);

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
        playerCurrentRegion.put(playerId, RegionKey.of(regionConfig, activeVariant));
        musicManager.playMusic(player, regionConfig, activeVariant);

        boolean newDiscovery = plugin.getPlayerDataManager().discoverRegion(
                player, regionConfig.worldName(), regionConfig.regionId());
        if (newDiscovery) {
            plugin.getMessageManager().send(player, "region-discovered",
                    Placeholder.parsed("region", regionConfig.resolveDisplayName()),
                    Placeholder.unparsed("region_id", regionConfig.regionId()),
                    Placeholder.unparsed("world", regionConfig.worldName()));
        }

        plugin.debug(player.getName() + " entered music region: " + regionConfig.regionId()
                + " in " + regionConfig.worldName()
                + (activeVariant != null ? " [" + activeVariant + "]" : ""));
    }

    private void handleNoMusicRegion(Player player) {
        UUID playerId = player.getUniqueId();
        cancelTransition(playerId);
        vanillaStopCounter.remove(playerId);

        RegionKey previousKey = playerCurrentRegion.remove(playerId);
        if (previousKey != null) {
            musicManager.stopMusic(player);
            plugin.debug(player.getName() + " left music region: " + previousKey);
        }
    }

    // --- Vanilla music suppression ---

    /**
     * Throttled vanilla-music stop. Sends a stopSound(MUSIC source) packet
     * every {@link #VANILLA_STOP_INTERVAL} timer cycles — but ONLY when the
     * player's active region track is on a non-MUSIC source. If our own track
     * is on MUSIC, the stop would silence it too (both share the source), so
     * we skip the periodic stop and rely on the single per-track stop in
     * {@link MusicManager#playTrack}.
     */
    private void tickVanillaStop(Player player) {
        RegionConfig active = musicManager.getCurrentRegionConfig(player);
        if (active != null && active.soundSource() == Sound.Source.MUSIC) return;

        UUID playerId = player.getUniqueId();
        int count = vanillaStopCounter.merge(playerId, 1, Integer::sum);
        if (count >= VANILLA_STOP_INTERVAL) {
            vanillaStopCounter.put(playerId, 0);
            player.stopSound(SoundStop.source(Sound.Source.MUSIC));
        }
    }

    // --- Variant resolution ---

    private VariantType resolveVariant(Player player, RegionConfig config) {
        // Admin override takes precedence over weather/time, even if the region
        // doesn't have that variant configured (resolveActiveTracks falls back
        // to the default tracks in that case).
        VariantType override = variantOverrides.get(player.getUniqueId());
        if (override != null) return override;

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

    // --- Variant override (admin /test command) ---

    public void setVariantOverride(UUID playerId, VariantType variant) {
        variantOverrides.put(playerId, variant);
    }

    public void clearVariantOverride(UUID playerId) {
        variantOverrides.remove(playerId);
    }

    public VariantType getVariantOverride(UUID playerId) {
        return variantOverrides.get(playerId);
    }

    // --- Region lookup helpers ---

    private RegionConfig findMusicForRegions(ApplicableRegionSet regions,
                                             RegionManager regionManager,
                                             String worldName) {
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

        // WG's ApplicableRegionSet iterator does NOT include the global region —
        // it's used only for default-flag calculation. Look it up explicitly so
        // admins can configure music for an entire world via the __global__ key.
        RegionConfig globalData = worldRegions.get("__global__");
        if (globalData != null) {
            ProtectedRegion globalRegion = regionManager.getRegion("__global__");
            int wgPriority = globalRegion != null ? globalRegion.getPriority() : 0;
            int priority = globalData.configPriority() == RegionConfig.USE_WORLDGUARD_PRIORITY
                    ? wgPriority
                    : globalData.configPriority();

            if (priority > highestPriority) {
                highestPriority = priority;
                highestPriorityMusic = globalData;
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

        return findMusicForRegions(regions, regionManager, world.getName());
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
        vanillaStopCounter.clear();
    }

    /**
     * Forces the next check for this player to re-evaluate the region.
     * Used by /toggle, /volume, /test commands after the player's state changes.
     */
    public void clearPlayerRegion(UUID playerId) {
        playerCurrentRegion.remove(playerId);
        vanillaStopCounter.remove(playerId);
        cancelTransition(playerId);

        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            scheduleForcedCheck(player, EVENT_CHECK_DELAY_TICKS);
        }
    }

}
