package dev.oakheart.regionmusic.managers;

import dev.oakheart.regionmusic.RegionMusic;
import dev.oakheart.regionmusic.model.RegionConfig;
import dev.oakheart.regionmusic.model.RegionConfig.VariantType;
import dev.oakheart.regionmusic.model.RegionTrack;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class MusicManager {

    private final RegionMusic plugin;
    // Concurrent: written on the main thread, but PlaceholderAPI consumers
    // (TAB) read these maps from async threads via RegionMusicPlaceholders.
    private final Map<UUID, PlayerMusicState> playerStates = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, PreviewState> previewStates = new java.util.concurrent.ConcurrentHashMap<>();

    public MusicManager(RegionMusic plugin) {
        this.plugin = plugin;
    }

    // --- Region Music ---

    public void playMusic(Player player, RegionConfig regionConfig, VariantType activeVariant) {
        UUID playerId = player.getUniqueId();

        if (previewStates.containsKey(playerId)) return;

        PlayerMusicState currentState = playerStates.get(playerId);

        if (currentState != null
                && currentState.regionConfig.isSameRegion(regionConfig)
                && currentState.activeVariant == activeVariant) {
            return;
        }

        stopMusicImmediately(player);

        List<RegionTrack> activeTracks = regionConfig.resolveActiveTracks(activeVariant);
        if (activeTracks.isEmpty()) return;

        int startIndex = regionConfig.order() == RegionConfig.PlaybackOrder.SHUFFLE
                ? ThreadLocalRandom.current().nextInt(activeTracks.size())
                : 0;

        PlayerMusicState state = new PlayerMusicState(
                regionConfig, activeVariant, activeTracks, startIndex);
        playerStates.put(playerId, state);

        playTrack(player, state, true);

        plugin.debug("Started music for " + player.getName() + ": "
                + activeTracks.get(startIndex).soundKeyString()
                + " in region " + regionConfig.regionId()
                + (activeVariant != null ? " [" + activeVariant + "]" : ""));
    }

    public boolean stopMusic(Player player) {
        UUID playerId = player.getUniqueId();
        PlayerMusicState state = playerStates.get(playerId);
        if (state == null) return false;

        RegionConfig region = state.regionConfig;

        stopMusicImmediately(player);
        playerStates.remove(playerId);

        plugin.getMessageManager().send(player, "music-stopped",
                Placeholder.parsed("region", region.resolveDisplayName()),
                Placeholder.unparsed("region_id", region.regionId()),
                Placeholder.unparsed("world", region.worldName()));
        plugin.debug("Stopped music for " + player.getName());
        return true;
    }

    /** Stops music without sending messages. Used for volume changes and transitions. */
    public void stopMusicSilently(Player player) {
        stopMusicImmediately(player);
        playerStates.remove(player.getUniqueId());
    }

    private void playTrack(Player player, PlayerMusicState state, boolean announce) {
        RegionTrack track = state.activeTracks.get(state.currentTrackIndex);

        stopSoundsForState(player, state);

        // Suppress any currently-playing vanilla biome music in the MUSIC
        // source before we start ours. This is the only place where we stop
        // the MUSIC source globally — doing it periodically would also kill
        // our own track, since both vanilla music and region music share the
        // same source category.
        if (plugin.getConfigManager().isStopVanillaMusic()) {
            player.stopSound(SoundStop.source(Sound.Source.MUSIC));
        }

        // Compute effective volume per track: track override (if set) replaces
        // the region's base volume; player's personal volume always scales on top.
        float baseVolume = track.resolveVolume(state.regionConfig.volume());
        float effectiveVolume = baseVolume
                * plugin.getPlayerDataManager().getEffectiveVolume(player.getUniqueId());

        // Positional playback at the player's location matches what /playsound
        // does and works for both short event sounds and long streamed tracks.
        // Entity-attached playback (Sound.Emitter.self()) is unreliable for
        // streamed OGG tracks under the MUSIC source.
        Sound sound = state.regionConfig.createSound(track, effectiveVolume);
        Location loc = player.getLocation();
        player.playSound(sound, loc.getX(), loc.getY(), loc.getZ());
        state.currentSoundKey = track.soundKey();
        state.lastEffectiveVolume = effectiveVolume;

        if (announce) {
            plugin.getMessageManager().send(player, "now-playing",
                    Placeholder.parsed("region", state.regionConfig.resolveDisplayName()),
                    Placeholder.unparsed("region_id", state.regionConfig.regionId()),
                    Placeholder.unparsed("world", state.regionConfig.worldName()),
                    Placeholder.unparsed("sound", track.displayName()));
        }

        // Schedule next track if looping or playlist with more tracks
        if (state.regionConfig.loop() && track.durationTicks() > 0) {
            scheduleAdvance(player, state, track.durationTicks());
        } else if (!state.regionConfig.loop() && state.activeTracks.size() > 1 && hasMoreTracks(state)) {
            scheduleAdvance(player, state, track.durationTicks());
        }
    }

    private void scheduleAdvance(Player player, PlayerMusicState state, long delayTicks) {
        UUID playerId = player.getUniqueId();
        state.nextTrackTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p == null || !p.isOnline()) {
                cleanupPlayer(playerId);
                return;
            }

            PlayerMusicState currentState = playerStates.get(playerId);
            if (currentState != state) return;

            advanceTrack(state);
            // Announce only when the playlist actually moved to a different
            // track: a single-track looping region "advances" back to the same
            // song and would re-chat "Now playing" every loop.
            playTrack(p, state, state.activeTracks.size() > 1);
            plugin.debug("Advanced track for " + p.getName() + ": "
                    + state.activeTracks.get(state.currentTrackIndex).soundKeyString());
        }, delayTicks);
    }

    private void advanceTrack(PlayerMusicState state) {
        if (state.regionConfig.order() == RegionConfig.PlaybackOrder.SHUFFLE) {
            if (state.activeTracks.size() > 1) {
                int next;
                do {
                    next = ThreadLocalRandom.current().nextInt(state.activeTracks.size());
                } while (next == state.currentTrackIndex);
                state.currentTrackIndex = next;
            }
        } else {
            state.currentTrackIndex = (state.currentTrackIndex + 1) % state.activeTracks.size();
        }
        state.tracksPlayed++;
    }

    private boolean hasMoreTracks(PlayerMusicState state) {
        return state.tracksPlayed < state.activeTracks.size() - 1;
    }

    private void stopSoundsForState(Player player, PlayerMusicState state) {
        if (state.currentSoundKey != null) {
            player.stopSound(SoundStop.named(state.currentSoundKey));
        }
    }

    private void stopMusicImmediately(Player player) {
        UUID playerId = player.getUniqueId();
        PlayerMusicState state = playerStates.get(playerId);
        if (state != null) {
            stopSoundsForState(player, state);
            if (state.nextTrackTask != null) {
                state.nextTrackTask.cancel();
            }
        }
    }

    // --- Preview ---

    public void startPreview(Player player, Key soundKey, float volume) {
        UUID playerId = player.getUniqueId();

        stopMusicImmediately(player);
        playerStates.remove(playerId);
        stopPreviewImmediately(player);

        // See playTrack for why positional playback is required for streamed tracks.
        Sound sound = Sound.sound(soundKey, Sound.Source.MUSIC, volume, 1.0f);
        Location loc = player.getLocation();
        player.playSound(sound, loc.getX(), loc.getY(), loc.getZ());

        previewStates.put(playerId, new PreviewState(soundKey));
    }

    public boolean stopPreview(Player player) {
        UUID playerId = player.getUniqueId();
        PreviewState state = previewStates.remove(playerId);
        if (state != null) {
            player.stopSound(SoundStop.named(state.soundKey));
            return true;
        }
        return false;
    }

    public boolean isPreviewing(Player player) {
        return previewStates.containsKey(player.getUniqueId());
    }

    private void stopPreviewImmediately(Player player) {
        PreviewState state = previewStates.remove(player.getUniqueId());
        if (state != null) {
            player.stopSound(SoundStop.named(state.soundKey));
        }
    }

    // --- Queries ---

    public boolean isPlayingMusic(Player player) {
        return playerStates.containsKey(player.getUniqueId());
    }

    public RegionConfig getCurrentRegionConfig(Player player) {
        PlayerMusicState state = playerStates.get(player.getUniqueId());
        return state != null ? state.regionConfig : null;
    }

    public RegionTrack getCurrentTrack(Player player) {
        PlayerMusicState state = playerStates.get(player.getUniqueId());
        if (state == null) return null;
        return state.activeTracks.get(state.currentTrackIndex);
    }

    public VariantType getCurrentVariant(Player player) {
        PlayerMusicState state = playerStates.get(player.getUniqueId());
        return state != null ? state.activeVariant : null;
    }

    public float getCurrentEffectiveVolume(Player player) {
        PlayerMusicState state = playerStates.get(player.getUniqueId());
        return state != null ? state.lastEffectiveVolume : 0f;
    }

    // --- Cleanup ---

    public void cleanup() {
        for (Map.Entry<UUID, PlayerMusicState> entry : playerStates.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                stopSoundsForState(player, entry.getValue());
            }
            if (entry.getValue().nextTrackTask != null) {
                entry.getValue().nextTrackTask.cancel();
            }
        }
        playerStates.clear();

        for (Map.Entry<UUID, PreviewState> entry : previewStates.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                player.stopSound(SoundStop.named(entry.getValue().soundKey));
            }
        }
        previewStates.clear();
    }

    public void cleanupPlayer(Player player) {
        cleanupPlayer(player.getUniqueId());
    }

    public void cleanupPlayer(UUID playerId) {
        PlayerMusicState state = playerStates.remove(playerId);
        if (state != null && state.nextTrackTask != null) {
            state.nextTrackTask.cancel();
        }

        PreviewState preview = previewStates.remove(playerId);
        if (preview != null) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.stopSound(SoundStop.named(preview.soundKey));
            }
        }
    }

    // --- Inner state classes ---

    static class PlayerMusicState {
        final RegionConfig regionConfig;
        final VariantType activeVariant;
        final List<RegionTrack> activeTracks;
        int currentTrackIndex;
        int tracksPlayed;
        Key currentSoundKey;
        BukkitTask nextTrackTask;
        float lastEffectiveVolume;

        PlayerMusicState(RegionConfig regionConfig, VariantType activeVariant,
                         List<RegionTrack> activeTracks, int startIndex) {
            this.regionConfig = regionConfig;
            this.activeVariant = activeVariant;
            this.activeTracks = activeTracks;
            this.currentTrackIndex = startIndex;
            this.tracksPlayed = 0;
        }
    }

    private record PreviewState(Key soundKey) {}
}
