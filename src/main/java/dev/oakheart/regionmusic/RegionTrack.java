package dev.oakheart.regionmusic;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Nullable;

public record RegionTrack(Key soundKey, long durationTicks, @Nullable String name) {

    public String soundKeyString() {
        return soundKey.asString();
    }

    /**
     * Returns the display name if set, otherwise the sound key string.
     */
    public String displayName() {
        return name != null && !name.isEmpty() ? name : soundKeyString();
    }
}
