package dev.oakheart.regionmusic.model;

import net.kyori.adventure.sound.Sound;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RegionConfig(
        String regionId,
        String worldName,
        @Nullable String displayName,
        float volume,
        boolean loop,
        Sound.Source soundSource,
        int configPriority,
        PlaybackOrder order,
        List<RegionTrack> tracks,
        Map<VariantType, List<RegionTrack>> variants
) {

    public RegionConfig {
        tracks = List.copyOf(tracks);
        if (variants.isEmpty()) {
            variants = Map.of();
        } else {
            var copy = new HashMap<VariantType, List<RegionTrack>>();
            variants.forEach((k, v) -> copy.put(k, List.copyOf(v)));
            variants = Map.copyOf(copy);
        }
    }

    /** Display name for player-facing messages. Falls back to the region ID. */
    public String resolveDisplayName() {
        return displayName != null && !displayName.isEmpty() ? displayName : regionId;
    }

    /** Sentinel value indicating the WorldGuard region priority should be used. */
    public static final int USE_WORLDGUARD_PRIORITY = Integer.MIN_VALUE;

    public enum PlaybackOrder { SEQUENTIAL, SHUFFLE }

    public enum VariantType {
        NIGHT, RAIN, THUNDER;

        /** Priority order: thunder > rain > night. Higher ordinal = higher priority. */
        public int priority() {
            return ordinal();
        }
    }

    public List<RegionTrack> resolveActiveTracks(VariantType activeVariant) {
        if (activeVariant != null) {
            List<RegionTrack> variantTracks = variants.get(activeVariant);
            if (variantTracks != null && !variantTracks.isEmpty()) {
                return variantTracks;
            }
        }
        return tracks;
    }

    public Sound createSound(RegionTrack track, float effectiveVolume) {
        return Sound.sound(track.soundKey(), soundSource, effectiveVolume, 1.0f);
    }

    public boolean isSameRegion(RegionConfig other) {
        if (other == null) return false;
        return Objects.equals(regionId, other.regionId)
                && Objects.equals(worldName, other.worldName);
    }
}
