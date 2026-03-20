package dev.oakheart.regionmusic;

import dev.oakheart.regionmusic.RegionConfig.VariantType;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class RegionMusicPlaceholders extends PlaceholderExpansion {

    private final RegionMusic plugin;

    public RegionMusicPlaceholders(RegionMusic plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "regionmusic";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        if (offlinePlayer == null) {
            return null;
        }

        // Placeholders that work for offline players
        switch (params.toLowerCase()) {
            case "enabled" -> {
                return String.valueOf(plugin.getPlayerDataManager().isMusicEnabled(offlinePlayer.getUniqueId()));
            }
            case "toggled" -> {
                return String.valueOf(!plugin.getPlayerDataManager().isMusicEnabled(offlinePlayer.getUniqueId()));
            }
            case "volume_personal" -> {
                return String.valueOf(plugin.getPlayerDataManager().getVolumePercent(offlinePlayer.getUniqueId()));
            }
        }

        // Placeholders that require online player
        Player player = offlinePlayer.getPlayer();
        if (player == null) {
            return null;
        }

        MusicManager mm = plugin.getMusicManager();
        RegionConfig currentConfig = mm.getCurrentRegionConfig(player);
        RegionTrack currentTrack = mm.getCurrentTrack(player);
        VariantType currentVariant = mm.getCurrentVariant(player);

        return switch (params.toLowerCase()) {
            case "playing" -> String.valueOf(currentConfig != null);
            case "sound" -> currentTrack != null ? currentTrack.soundKeyString() : "";
            case "region" -> currentConfig != null ? currentConfig.regionId() : "";
            case "world" -> currentConfig != null ? currentConfig.worldName() : "";
            case "volume" -> currentConfig != null ? String.valueOf((int) (mm.getCurrentEffectiveVolume(player) * 100)) : "";
            case "volume_decimal" -> currentConfig != null ? String.format("%.2f", mm.getCurrentEffectiveVolume(player)) : "";
            case "track" -> currentTrack != null ? currentTrack.displayName() : "";
            case "variant" -> currentVariant != null ? currentVariant.name().toLowerCase() : "";
            default -> null;
        };
    }
}
