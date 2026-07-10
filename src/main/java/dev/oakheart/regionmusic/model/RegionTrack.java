package dev.oakheart.regionmusic.model;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Nullable;

/**
 * One track in a region's playlist (or the sole track for a single-sound region).
 *
 * <p>{@code volume} overrides the parent region's {@code volume} when set;
 * the player's personal volume always scales on top of whichever wins.
 */
public record RegionTrack(
        Key soundKey,
        long durationTicks,
        @Nullable String name,
        @Nullable Float volume) {

    public String soundKeyString() {
        return soundKey.asString();
    }

    public String displayName() {
        return name != null && !name.isEmpty() ? name : soundKeyString();
    }

    /** Resolve the base track volume: per-track override if set, else the region's volume. */
    public float resolveVolume(float regionVolume) {
        return volume != null ? volume : regionVolume;
    }
}
