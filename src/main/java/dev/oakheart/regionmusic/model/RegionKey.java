package dev.oakheart.regionmusic.model;

import org.jetbrains.annotations.Nullable;

/**
 * Identifies a region music context: which region in which world,
 * and which variant (if any) is currently active.
 */
public record RegionKey(String worldName, String regionId, @Nullable RegionConfig.VariantType variant) {

    public static RegionKey of(RegionConfig config, @Nullable RegionConfig.VariantType variant) {
        return new RegionKey(config.worldName(), config.regionId(), variant);
    }

    /** Two keys point at the same configured region regardless of active variant. */
    public boolean isSameRegion(RegionKey other) {
        if (other == null) return false;
        return worldName.equals(other.worldName) && regionId.equals(other.regionId);
    }
}
