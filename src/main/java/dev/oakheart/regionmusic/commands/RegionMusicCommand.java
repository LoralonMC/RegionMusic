package dev.oakheart.regionmusic.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.oakheart.command.CommandRegistrar;
import dev.oakheart.message.MessageManager;
import dev.oakheart.regionmusic.RegionMusic;
import dev.oakheart.regionmusic.managers.MusicManager;
import dev.oakheart.regionmusic.model.RegionConfig;
import dev.oakheart.regionmusic.model.RegionConfig.VariantType;
import dev.oakheart.regionmusic.model.RegionTrack;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SuppressWarnings("UnstableApiUsage")
public class RegionMusicCommand {

    private final RegionMusic plugin;

    private static final List<String> VANILLA_MUSIC_DISCS = List.of(
            "minecraft:music_disc.11",
            "minecraft:music_disc.13",
            "minecraft:music_disc.5",
            "minecraft:music_disc.blocks",
            "minecraft:music_disc.cat",
            "minecraft:music_disc.chirp",
            "minecraft:music_disc.creator",
            "minecraft:music_disc.creator_music_box",
            "minecraft:music_disc.far",
            "minecraft:music_disc.mall",
            "minecraft:music_disc.mellohi",
            "minecraft:music_disc.otherside",
            "minecraft:music_disc.pigstep",
            "minecraft:music_disc.precipice",
            "minecraft:music_disc.relic",
            "minecraft:music_disc.stal",
            "minecraft:music_disc.strad",
            "minecraft:music_disc.wait",
            "minecraft:music_disc.ward"
    );

    public RegionMusicCommand(RegionMusic plugin) {
        this.plugin = plugin;
    }

    public void register() {
        LiteralCommandNode<CommandSourceStack> rootNode = Commands.literal("regionmusic")
                .executes(ctx -> {
                    if (ctx.getSource().getSender().hasPermission("regionmusic.admin")) {
                        sendHelp(ctx.getSource().getSender());
                    } else {
                        sendPlayerHelp(ctx.getSource().getSender());
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .then(buildReloadCommand())
                .then(buildToggleCommand())
                .then(buildStatusCommand())
                .then(buildVolumeCommand())
                .then(buildPreviewCommand())
                .then(buildTestCommand())
                .then(buildListCommand())
                .then(Commands.literal("help")
                        .executes(ctx -> {
                            if (ctx.getSource().getSender().hasPermission("regionmusic.admin")) {
                                sendHelp(ctx.getSource().getSender());
                            } else {
                                sendPlayerHelp(ctx.getSource().getSender());
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .build();

        CommandRegistrar.register(plugin, rootNode, "Regional music control", List.of("rmusic"));
    }

    private MessageManager messages() {
        return plugin.getMessageManager();
    }

    // --- Reload ---

    private LiteralCommandNode<CommandSourceStack> buildReloadCommand() {
        return Commands.literal("reload")
                .requires(source -> source.getSender().hasPermission("regionmusic.admin"))
                .executes(ctx -> {
                    boolean success = plugin.reload();
                    if (success) {
                        messages().sendCommand(ctx.getSource().getSender(), "config-reloaded",
                                Placeholder.unparsed("count",
                                        String.valueOf(plugin.getConfigManager().countConfiguredRegions())));
                    } else {
                        messages().sendCommand(ctx.getSource().getSender(), "reload-failed");
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    // --- Toggle ---

    private LiteralCommandNode<CommandSourceStack> buildToggleCommand() {
        return Commands.literal("toggle")
                .requires(source -> source.getSender().hasPermission("regionmusic.toggle"))
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    if (!(sender instanceof Player player)) {
                        messages().sendCommand(sender, "console-toggle-usage");
                        return Command.SINGLE_SUCCESS;
                    }
                    boolean enabled = plugin.getPlayerDataManager().toggle(player.getUniqueId());
                    if (enabled) {
                        plugin.getRegionListener().clearPlayerRegion(player.getUniqueId());
                        messages().sendCommand(player, "music-enabled");
                    } else {
                        plugin.getMusicManager().stopMusic(player);
                        messages().sendCommand(player, "music-disabled");
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("on")
                        .requires(source -> source.getSender() instanceof Player)
                        .executes(ctx -> {
                            Player player = (Player) ctx.getSource().getSender();
                            plugin.getPlayerDataManager().enable(player.getUniqueId());
                            plugin.getRegionListener().clearPlayerRegion(player.getUniqueId());
                            messages().sendCommand(player, "music-enabled");
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(Commands.literal("off")
                        .requires(source -> source.getSender() instanceof Player)
                        .executes(ctx -> {
                            Player player = (Player) ctx.getSource().getSender();
                            plugin.getPlayerDataManager().disable(player.getUniqueId());
                            plugin.getMusicManager().stopMusic(player);
                            messages().sendCommand(player, "music-disabled");
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(Commands.argument("player", ArgumentTypes.player())
                        .requires(source -> source.getSender().hasPermission("regionmusic.admin"))
                        .executes(ctx -> {
                            PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
                            List<Player> players = resolver.resolve(ctx.getSource());

                            if (players.isEmpty()) {
                                messages().sendCommand(ctx.getSource().getSender(), "player-not-found");
                                return Command.SINGLE_SUCCESS;
                            }

                            Player target = players.getFirst();
                            boolean enabled = plugin.getPlayerDataManager().toggle(target.getUniqueId());

                            if (enabled) {
                                plugin.getRegionListener().clearPlayerRegion(target.getUniqueId());
                                messages().sendCommand(ctx.getSource().getSender(), "music-enabled-for",
                                        Placeholder.unparsed("player", target.getName()));
                            } else {
                                plugin.getMusicManager().stopMusic(target);
                                messages().sendCommand(ctx.getSource().getSender(), "music-disabled-for",
                                        Placeholder.unparsed("player", target.getName()));
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("on")
                                .executes(ctx -> {
                                    PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
                                    List<Player> players = resolver.resolve(ctx.getSource());

                                    if (players.isEmpty()) {
                                        messages().sendCommand(ctx.getSource().getSender(), "player-not-found");
                                        return Command.SINGLE_SUCCESS;
                                    }

                                    Player target = players.getFirst();
                                    plugin.getPlayerDataManager().enable(target.getUniqueId());
                                    plugin.getRegionListener().clearPlayerRegion(target.getUniqueId());
                                    messages().sendCommand(ctx.getSource().getSender(), "music-enabled-for",
                                            Placeholder.unparsed("player", target.getName()));
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                        .then(Commands.literal("off")
                                .executes(ctx -> {
                                    PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
                                    List<Player> players = resolver.resolve(ctx.getSource());

                                    if (players.isEmpty()) {
                                        messages().sendCommand(ctx.getSource().getSender(), "player-not-found");
                                        return Command.SINGLE_SUCCESS;
                                    }

                                    Player target = players.getFirst();
                                    plugin.getPlayerDataManager().disable(target.getUniqueId());
                                    plugin.getMusicManager().stopMusic(target);
                                    messages().sendCommand(ctx.getSource().getSender(), "music-disabled-for",
                                            Placeholder.unparsed("player", target.getName()));
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                )
                .build();
    }

    // --- Status ---

    private LiteralCommandNode<CommandSourceStack> buildStatusCommand() {
        return Commands.literal("status")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    if (!(sender instanceof Player player)) {
                        messages().sendCommand(sender, "console-status-usage");
                        return Command.SINGLE_SUCCESS;
                    }
                    sendStatusFor(player, player);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("player", ArgumentTypes.player())
                        .requires(source -> source.getSender().hasPermission("regionmusic.admin"))
                        .executes(ctx -> {
                            PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
                            List<Player> players = resolver.resolve(ctx.getSource());

                            if (players.isEmpty()) {
                                messages().sendCommand(ctx.getSource().getSender(), "player-not-found");
                                return Command.SINGLE_SUCCESS;
                            }

                            Player target = players.getFirst();
                            MusicManager mm = plugin.getMusicManager();
                            RegionConfig currentConfig = mm.getCurrentRegionConfig(target);

                            if (currentConfig != null) {
                                RegionTrack track = mm.getCurrentTrack(target);
                                messages().sendCommand(ctx.getSource().getSender(), "status-playing-for",
                                        Placeholder.unparsed("player", target.getName()),
                                        Placeholder.parsed("region", currentConfig.resolveDisplayName()),
                                        Placeholder.unparsed("region_id", currentConfig.regionId()),
                                        Placeholder.unparsed("world", currentConfig.worldName()),
                                        Placeholder.unparsed("sound", track != null ? track.displayName() : "unknown"),
                                        Placeholder.unparsed("volume",
                                                String.format("%.0f%%", mm.getCurrentEffectiveVolume(target) * 100)));
                            } else {
                                messages().sendCommand(ctx.getSource().getSender(), "status-not-playing-for",
                                        Placeholder.unparsed("player", target.getName()));
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .build();
    }

    private void sendStatusFor(Player sender, Player target) {
        MusicManager mm = plugin.getMusicManager();
        RegionConfig currentConfig = mm.getCurrentRegionConfig(target);

        if (currentConfig != null) {
            RegionTrack track = mm.getCurrentTrack(target);
            messages().sendCommand(sender, "status-playing",
                    Placeholder.parsed("region", currentConfig.resolveDisplayName()),
                    Placeholder.unparsed("region_id", currentConfig.regionId()),
                    Placeholder.unparsed("world", currentConfig.worldName()),
                    Placeholder.unparsed("sound", track != null ? track.displayName() : "unknown"),
                    Placeholder.unparsed("volume",
                            String.format("%.0f%%", mm.getCurrentEffectiveVolume(target) * 100)));
        } else {
            messages().sendCommand(sender, "status-not-playing");
        }
    }

    // --- Volume ---

    private LiteralCommandNode<CommandSourceStack> buildVolumeCommand() {
        return Commands.literal("volume")
                .requires(source -> source.getSender().hasPermission("regionmusic.volume"))
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    if (!(sender instanceof Player player)) {
                        messages().sendCommand(sender, "console-volume-usage");
                        return Command.SINGLE_SUCCESS;
                    }
                    int vol = plugin.getPlayerDataManager().getVolumePercent(player.getUniqueId());
                    messages().sendCommand(player, "volume-current",
                            Placeholder.unparsed("volume", String.valueOf(vol)));
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("value", IntegerArgumentType.integer(0, 100))
                        .requires(source -> source.getSender() instanceof Player)
                        .executes(ctx -> {
                            Player player = (Player) ctx.getSource().getSender();
                            int value = IntegerArgumentType.getInteger(ctx, "value");

                            plugin.getPlayerDataManager().setVolumePercent(player.getUniqueId(), value);
                            messages().sendCommand(player, "volume-set",
                                    Placeholder.unparsed("volume", String.valueOf(value)));

                            plugin.getMusicManager().stopMusicSilently(player);
                            plugin.getRegionListener().clearPlayerRegion(player.getUniqueId());
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(Commands.argument("player", ArgumentTypes.player())
                        .requires(source -> source.getSender().hasPermission("regionmusic.admin"))
                        .then(Commands.argument("value", IntegerArgumentType.integer(0, 100))
                                .executes(ctx -> {
                                    PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
                                    List<Player> players = resolver.resolve(ctx.getSource());

                                    if (players.isEmpty()) {
                                        messages().sendCommand(ctx.getSource().getSender(), "player-not-found");
                                        return Command.SINGLE_SUCCESS;
                                    }

                                    Player target = players.getFirst();
                                    int value = IntegerArgumentType.getInteger(ctx, "value");

                                    plugin.getPlayerDataManager().setVolumePercent(target.getUniqueId(), value);
                                    messages().sendCommand(ctx.getSource().getSender(), "volume-set-for",
                                            Placeholder.unparsed("player", target.getName()),
                                            Placeholder.unparsed("volume", String.valueOf(value)));

                                    plugin.getMusicManager().stopMusicSilently(target);
                                    plugin.getRegionListener().clearPlayerRegion(target.getUniqueId());
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                )
                .build();
    }

    // --- Preview ---

    private LiteralCommandNode<CommandSourceStack> buildPreviewCommand() {
        return Commands.literal("preview")
                .requires(source -> source.getSender() instanceof Player
                        && source.getSender().hasPermission("regionmusic.admin"))
                .then(Commands.literal("stop")
                        .executes(ctx -> {
                            Player player = (Player) ctx.getSource().getSender();
                            if (plugin.getMusicManager().stopPreview(player)) {
                                messages().sendCommand(player, "preview-stopped");
                                plugin.getRegionListener().clearPlayerRegion(player.getUniqueId());
                            } else {
                                messages().sendCommand(player, "preview-not-playing");
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(Commands.argument("sound", StringArgumentType.string())
                        .suggests(this::suggestSounds)
                        .executes(ctx -> {
                            Player player = (Player) ctx.getSource().getSender();
                            String soundStr = StringArgumentType.getString(ctx, "sound");
                            return executePreview(player, soundStr, 1.0f);
                        })
                        .then(Commands.argument("volume", FloatArgumentType.floatArg(0.0f, 1.0f))
                                .executes(ctx -> {
                                    Player player = (Player) ctx.getSource().getSender();
                                    String soundStr = StringArgumentType.getString(ctx, "sound");
                                    float volume = FloatArgumentType.getFloat(ctx, "volume");
                                    return executePreview(player, soundStr, volume);
                                })
                        )
                )
                .build();
    }

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestSounds(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String partial = builder.getRemaining().toLowerCase();

        // Configured region sounds (current playlist + variants) — deduped
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (Map<String, RegionConfig> worldRegions : plugin.getConfigManager().getRegionData().values()) {
            for (RegionConfig region : worldRegions.values()) {
                for (RegionTrack track : region.tracks()) {
                    seen.add(track.soundKeyString());
                }
                for (List<RegionTrack> variant : region.variants().values()) {
                    for (RegionTrack track : variant) {
                        seen.add(track.soundKeyString());
                    }
                }
            }
        }
        seen.addAll(VANILLA_MUSIC_DISCS);

        for (String sound : seen) {
            if (sound.toLowerCase().startsWith(partial)) {
                builder.suggest(sound);
            }
        }
        return builder.buildFuture();
    }

    private int executePreview(Player player, String soundStr, float volume) {
        Key soundKey;
        try {
            soundKey = Key.key(soundStr);
        } catch (Exception e) {
            messages().sendCommand(player, "invalid-sound",
                    Placeholder.unparsed("sound", soundStr));
            return Command.SINGLE_SUCCESS;
        }

        plugin.getMusicManager().startPreview(player, soundKey, volume);
        messages().sendCommand(player, "preview-started",
                Placeholder.unparsed("sound", soundStr));
        return Command.SINGLE_SUCCESS;
    }

    // --- Variant test (admin) ---

    private LiteralCommandNode<CommandSourceStack> buildTestCommand() {
        return Commands.literal("test")
                .requires(source -> source.getSender() instanceof Player
                        && source.getSender().hasPermission("regionmusic.admin"))
                .executes(ctx -> {
                    Player player = (Player) ctx.getSource().getSender();
                    VariantType current = plugin.getRegionListener().getVariantOverride(player.getUniqueId());
                    if (current == null) {
                        messages().sendCommand(player, "test-variant-none");
                    } else {
                        messages().sendCommand(player, "test-variant-set",
                                Placeholder.unparsed("variant", current.name().toLowerCase()));
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("clear")
                        .executes(ctx -> {
                            Player player = (Player) ctx.getSource().getSender();
                            plugin.getRegionListener().clearVariantOverride(player.getUniqueId());
                            plugin.getRegionListener().clearPlayerRegion(player.getUniqueId());
                            messages().sendCommand(player, "test-variant-cleared");
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(Commands.literal("night").executes(ctx -> applyOverride(ctx, VariantType.NIGHT)))
                .then(Commands.literal("rain").executes(ctx -> applyOverride(ctx, VariantType.RAIN)))
                .then(Commands.literal("thunder").executes(ctx -> applyOverride(ctx, VariantType.THUNDER)))
                .build();
    }

    private int applyOverride(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                              VariantType variant) {
        Player player = (Player) ctx.getSource().getSender();
        plugin.getRegionListener().setVariantOverride(player.getUniqueId(), variant);
        plugin.getRegionListener().clearPlayerRegion(player.getUniqueId());
        messages().sendCommand(player, "test-variant-set",
                Placeholder.unparsed("variant", variant.name().toLowerCase()));
        return Command.SINGLE_SUCCESS;
    }

    // --- List ---

    private LiteralCommandNode<CommandSourceStack> buildListCommand() {
        return Commands.literal("list")
                .requires(source -> source.getSender().hasPermission("regionmusic.admin"))
                .executes(ctx -> {
                    var sender = ctx.getSource().getSender();
                    var regionData = plugin.getConfigManager().getRegionData();

                    if (regionData.isEmpty()) {
                        messages().sendCommand(sender, "list-empty");
                        return Command.SINGLE_SUCCESS;
                    }

                    String currentRegionId = null;
                    String currentWorldName = null;
                    if (sender instanceof Player player) {
                        RegionConfig current = plugin.getMusicManager().getCurrentRegionConfig(player);
                        if (current != null) {
                            currentRegionId = current.regionId();
                            currentWorldName = current.worldName();
                        }
                    }

                    messages().sendCommand(sender, "list-header");

                    for (Map.Entry<String, Map<String, RegionConfig>> worldEntry : regionData.entrySet()) {
                        String worldName = worldEntry.getKey();
                        messages().sendCommand(sender, "list-world-header",
                                Placeholder.unparsed("world", worldName));

                        for (Map.Entry<String, RegionConfig> regionEntry : worldEntry.getValue().entrySet()) {
                            String regionId = regionEntry.getKey();
                            RegionConfig config = regionEntry.getValue();
                            String sound = config.tracks().isEmpty() ? "none" : config.tracks().getFirst().displayName();

                            if (regionId.equals(currentRegionId) && worldName.equals(currentWorldName)) {
                                messages().sendCommand(sender, "list-entry-current",
                                        Placeholder.parsed("region", config.resolveDisplayName()),
                                        Placeholder.unparsed("region_id", regionId),
                                        Placeholder.unparsed("sound", sound));
                            } else {
                                messages().sendCommand(sender, "list-entry",
                                        Placeholder.parsed("region", config.resolveDisplayName()),
                                        Placeholder.unparsed("region_id", regionId),
                                        Placeholder.unparsed("sound", sound));
                            }
                        }
                    }

                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    // --- Help ---

    private static final List<String> ADMIN_HELP_KEYS = List.of(
            "help-header",
            "help-reload",
            "help-toggle",
            "help-toggle-player",
            "help-status",
            "help-status-player",
            "help-volume",
            "help-volume-set",
            "help-volume-player",
            "help-preview",
            "help-preview-stop",
            "help-test",
            "help-list",
            "help-help"
    );

    private static final List<String> PLAYER_HELP_KEYS = List.of(
            "player-help-header",
            "player-help-toggle",
            "player-help-status",
            "player-help-volume"
    );

    private void sendHelp(CommandSender sender) {
        sendJoinedHelp(sender, ADMIN_HELP_KEYS);
    }

    private void sendPlayerHelp(CommandSender sender) {
        sendJoinedHelp(sender, PLAYER_HELP_KEYS);
    }

    private void sendJoinedHelp(CommandSender sender, List<String> keys) {
        var config = messages().getConfig();
        MiniMessage mm = MiniMessage.miniMessage();
        List<Component> lines = new ArrayList<>(keys.size());
        for (String key : keys) {
            String text = config.getString("commands." + key, "");
            if (text.isEmpty()) continue;
            lines.add(mm.deserialize(text));
        }
        if (lines.isEmpty()) return;
        sender.sendMessage(Component.join(JoinConfiguration.newlines(), lines));
    }
}
