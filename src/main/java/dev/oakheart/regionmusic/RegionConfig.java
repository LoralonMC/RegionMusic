package dev.oakheart.regionmusic;

import net.kyori.adventure.sound.Sound;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RegionConfig(
        String regionId,
        String worldName,
        float volume,
        boolean loop,
        Sound.Source soundSource,
        int configPriority,
        PlaybackOrder order,
        List<RegionTrack> tracks,
        Map<VariantType, List<RegionTrack>> variants
) {

    /** Compact constructor — stores immutable copies of tracks and variants. */
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

    /**
     * Resolves which track list to use based on the active variant.
     * Falls back to default tracks if the variant has no override.
     */
    public List<RegionTrack> resolveActiveTracks(VariantType activeVariant) {
        if (activeVariant != null) {
            List<RegionTrack> variantTracks = variants.get(activeVariant);
            if (variantTracks != null && !variantTracks.isEmpty()) {
                return variantTracks;
            }
        }
        return tracks;
    }

    /**
     * Creates an Adventure Sound for the given track at the given effective volume.
     */
    public Sound createSound(RegionTrack track, float effectiveVolume) {
        return Sound.sound(track.soundKey(), soundSource, effectiveVolume, 1.0f);
    }

    /**
     * Checks if this config represents the same region as another (same regionId + worldName).
     */
    public boolean isSameRegion(RegionConfig other) {
        if (other == null) return false;
        return Objects.equals(regionId, other.regionId)
                && Objects.equals(worldName, other.worldName);
    }
}
