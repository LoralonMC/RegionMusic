package dev.oakheart.regionmusic;

import dev.oakheart.regionmusic.RegionConfig.VariantType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class MusicManager {

    private final RegionMusic plugin;
    private final Map<UUID, PlayerMusicState> playerStates = new ConcurrentHashMap<>();
    private final Map<UUID, PreviewState> previewStates = new ConcurrentHashMap<>();

    public MusicManager(RegionMusic plugin) {
        this.plugin = plugin;
    }

    // --- Region Music ---

    public void playMusic(Player player, RegionConfig regionConfig, VariantType activeVariant) {
        UUID playerId = player.getUniqueId();

        // Don't interrupt previews
        if (previewStates.containsKey(playerId)) return;

        PlayerMusicState currentState = playerStates.get(playerId);

        // If already playing the same region+variant, do nothing
        if (currentState != null
                && currentState.regionConfig.isSameRegion(regionConfig)
                && currentState.activeVariant == activeVariant) {
            return;
        }

        // Stop current music
        stopMusicImmediately(player);

        // Resolve tracks for the active variant
        List<RegionTrack> activeTracks = regionConfig.resolveActiveTracks(activeVariant);
        if (activeTracks.isEmpty()) return;

        float effectiveVolume = regionConfig.volume()
                * plugin.getPlayerDataManager().getEffectiveVolume(playerId);

        // Determine starting track index
        int startIndex = regionConfig.order() == RegionConfig.PlaybackOrder.SHUFFLE
                ? ThreadLocalRandom.current().nextInt(activeTracks.size())
                : 0;

        PlayerMusicState state = new PlayerMusicState(
                regionConfig, activeVariant, activeTracks, effectiveVolume, startIndex);
        playerStates.put(playerId, state);

        // Play the first track
        playTrack(player, state);

        // Send now playing message
        RegionTrack currentTrack = activeTracks.get(startIndex);
        plugin.getMessageManager().send(player, "now-playing",
                Placeholder.unparsed("region", regionConfig.regionId()),
                Placeholder.unparsed("world", regionConfig.worldName()),
                Placeholder.unparsed("sound", currentTrack.displayName()));

        plugin.debug("Started music for " + player.getName() + ": " + currentTrack.soundKeyString()
                + " in region " + regionConfig.regionId()
                + (activeVariant != null ? " [" + activeVariant + "]" : ""));
    }

    public void stopMusic(Player player) {
        UUID playerId = player.getUniqueId();
        PlayerMusicState state = playerStates.get(playerId);

        if (state != null) {
            String regionId = state.regionConfig.regionId();
            String worldName = state.regionConfig.worldName();

            stopMusicImmediately(player);
            playerStates.remove(playerId);

            plugin.getMessageManager().send(player, "music-stopped",
                    Placeholder.unparsed("region", regionId),
                    Placeholder.unparsed("world", worldName));
            plugin.debug("Stopped music for " + player.getName());
        }
    }

    /**
     * Stops music without sending messages. Used for volume changes and transitions.
     */
    public void stopMusicSilently(Player player) {
        stopMusicImmediately(player);
        playerStates.remove(player.getUniqueId());
    }

    private void playTrack(Player player, PlayerMusicState state) {
        RegionTrack track = state.activeTracks.get(state.currentTrackIndex);

        // Stop any previous sound from this state
        stopSoundsForState(player, state);

        // Suppress any currently-playing vanilla biome music in the MUSIC
        // source before we start ours. This is the only place where we stop
        // the MUSIC source globally — doing it periodically would also kill
        // our own track, since both vanilla music and region music share the
        // same source category.
        if (plugin.getConfigManager().isStopVanillaMusic()) {
            player.stopSound(SoundStop.source(Sound.Source.MUSIC));
        }

        // Play the new sound at the player's position. Entity-attached playback
        // (Sound.Emitter.self()) is unreliable for streamed OGG tracks under the
        // MUSIC source — the client treats music as a positional/global sound,
        // not an entity sound. Positional playback at the player's location
        // matches what /playsound does and works for both short event sounds
        // and long streamed tracks.
        Sound sound = state.regionConfig.createSound(track, state.effectiveVolume);
        Location loc = player.getLocation();
        player.playSound(sound, loc.getX(), loc.getY(), loc.getZ());
        state.currentSoundKey = track.soundKey();

        // Schedule next track if looping or playlist with more tracks
        if (state.regionConfig.loop() && track.durationTicks() > 0) {
            state.nextTrackTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player p = Bukkit.getPlayer(player.getUniqueId());
                if (p == null || !p.isOnline()) {
                    cleanupPlayer(player.getUniqueId());
                    return;
                }

                PlayerMusicState currentState = playerStates.get(player.getUniqueId());
                if (currentState != state) return;

                advanceTrack(state);
                playTrack(p, state);

                plugin.debug("Advanced track for " + p.getName() + ": "
                        + state.activeTracks.get(state.currentTrackIndex).soundKeyString());
            }, track.durationTicks());
        } else if (!state.regionConfig.loop() && state.activeTracks.size() > 1) {
            // Non-looping playlist: play through once
            if (hasMoreTracks(state)) {
                state.nextTrackTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    Player p = Bukkit.getPlayer(player.getUniqueId());
                    if (p == null || !p.isOnline()) {
                        cleanupPlayer(player.getUniqueId());
                        return;
                    }

                    PlayerMusicState currentState = playerStates.get(player.getUniqueId());
                    if (currentState != state) return;

                    advanceTrack(state);
                    playTrack(p, state);
                }, track.durationTicks());
            }
            // else: last track in non-looping playlist, just let it play out
        }
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
            // Single track shuffle: index stays 0
        } else {
            state.currentTrackIndex = (state.currentTrackIndex + 1) % state.activeTracks.size();
        }
        state.tracksPlayed++;
    }

    private boolean hasMoreTracks(PlayerMusicState state) {
        // For non-looping playlists: check if we've played through all tracks
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

        // Stop region music if playing
        stopMusicImmediately(player);
        playerStates.remove(playerId);

        // Stop any existing preview
        stopPreviewImmediately(player);

        // Play preview sound at the player's position (see playTrack for why
        // positional playback is required for streamed music tracks).
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
        return state != null ? state.effectiveVolume : 0f;
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
        final float effectiveVolume;
        int currentTrackIndex;
        int tracksPlayed;
        Key currentSoundKey;
        BukkitTask nextTrackTask;

        PlayerMusicState(RegionConfig regionConfig, VariantType activeVariant,
                         List<RegionTrack> activeTracks, float effectiveVolume, int startIndex) {
            this.regionConfig = regionConfig;
            this.activeVariant = activeVariant;
            this.activeTracks = activeTracks;
            this.effectiveVolume = effectiveVolume;
            this.currentTrackIndex = startIndex;
            this.tracksPlayed = 0;
        }
    }

    private record PreviewState(Key soundKey) {}
}
