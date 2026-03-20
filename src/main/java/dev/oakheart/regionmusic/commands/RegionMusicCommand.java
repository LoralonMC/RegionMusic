package dev.oakheart.regionmusic.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.oakheart.regionmusic.MusicManager;
import dev.oakheart.regionmusic.RegionConfig;
import dev.oakheart.regionmusic.RegionMusic;
import dev.oakheart.regionmusic.RegionTrack;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;

@SuppressWarnings("UnstableApiUsage")
public class RegionMusicCommand {

    private final RegionMusic plugin;

    public RegionMusicCommand(RegionMusic plugin) {
        this.plugin = plugin;
    }

    public void register() {
        LifecycleEventManager<Plugin> manager = plugin.getLifecycleManager();
        manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();

            LiteralCommandNode<CommandSourceStack> rootNode = Commands.literal("regionmusic")
                    .executes(ctx -> {
                        if (ctx.getSource().getSender().hasPermission("regionmusic.admin")) {
                            sendHelp(ctx.getSource());
                        } else {
                            sendPlayerHelp(ctx.getSource());
                        }
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(buildReloadCommand())
                    .then(buildToggleCommand())
                    .then(buildStatusCommand())
                    .then(buildVolumeCommand())
                    .then(buildPreviewCommand())
                    .then(buildListCommand())
                    .then(Commands.literal("help")
                            .executes(ctx -> {
                                if (ctx.getSource().getSender().hasPermission("regionmusic.admin")) {
                                    sendHelp(ctx.getSource());
                                } else {
                                    sendPlayerHelp(ctx.getSource());
                                }
                                return Command.SINGLE_SUCCESS;
                            })
                    )
                    .build();

            commands.register(rootNode, "Regional music control", List.of("rmusic"));
        });
    }

    // --- Reload ---

    private LiteralCommandNode<CommandSourceStack> buildReloadCommand() {
        return Commands.literal("reload")
                .requires(source -> source.getSender().hasPermission("regionmusic.admin"))
                .executes(ctx -> {
                    boolean success = plugin.reload();
                    if (success) {
                        plugin.getMessageManager().sendConfigReloaded(
                                ctx.getSource().getSender(),
                                plugin.getConfigManager().countConfiguredRegions()
                        );
                    } else {
                        plugin.getMessageManager().sendReloadFailed(ctx.getSource().getSender());
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
                    // Self-toggle — player only
                    CommandSender sender = ctx.getSource().getSender();
                    if (!(sender instanceof Player player)) {
                        plugin.getMessageManager().sendRaw(sender,
                                "<#D89B6A>Usage: <#FCD472>/regionmusic toggle \\<player> [on|off]");
                        return Command.SINGLE_SUCCESS;
                    }
                    boolean enabled = plugin.getPlayerDataManager().toggle(player.getUniqueId());
                    if (enabled) {
                        plugin.getRegionListener().clearPlayerRegion(player.getUniqueId());
                        plugin.getMessageManager().sendMusicEnabled(player);
                    } else {
                        plugin.getMusicManager().stopMusic(player);
                        plugin.getMessageManager().sendMusicDisabled(player);
                    }
                    return Command.SINGLE_SUCCESS;
                })
                // Self on/off
                .then(Commands.literal("on")
                        .requires(source -> source.getSender() instanceof Player)
                        .executes(ctx -> {
                            Player player = (Player) ctx.getSource().getSender();
                            plugin.getPlayerDataManager().enable(player.getUniqueId());
                            plugin.getRegionListener().clearPlayerRegion(player.getUniqueId());
                            plugin.getMessageManager().sendMusicEnabled(player);
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(Commands.literal("off")
                        .requires(source -> source.getSender() instanceof Player)
                        .executes(ctx -> {
                            Player player = (Player) ctx.getSource().getSender();
                            plugin.getPlayerDataManager().disable(player.getUniqueId());
                            plugin.getMusicManager().stopMusic(player);
                            plugin.getMessageManager().sendMusicDisabled(player);
                            return Command.SINGLE_SUCCESS;
                        })
                )
                // Admin: toggle for another player (console-accessible)
                .then(Commands.argument("player", ArgumentTypes.player())
                        .requires(source -> source.getSender().hasPermission("regionmusic.admin"))
                        .executes(ctx -> {
                            PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
                            List<Player> players = resolver.resolve(ctx.getSource());

                            if (players.isEmpty()) {
                                plugin.getMessageManager().sendPlayerNotFound(ctx.getSource().getSender());
                                return Command.SINGLE_SUCCESS;
                            }

                            Player target = players.getFirst();
                            boolean enabled = plugin.getPlayerDataManager().toggle(target.getUniqueId());

                            if (enabled) {
                                plugin.getRegionListener().clearPlayerRegion(target.getUniqueId());
                                plugin.getMessageManager().sendMusicEnabledFor(ctx.getSource().getSender(), target.getName());
                            } else {
                                plugin.getMusicManager().stopMusic(target);
                                plugin.getMessageManager().sendMusicDisabledFor(ctx.getSource().getSender(), target.getName());
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("on")
                                .executes(ctx -> {
                                    PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
                                    List<Player> players = resolver.resolve(ctx.getSource());

                                    if (players.isEmpty()) {
                                        plugin.getMessageManager().sendPlayerNotFound(ctx.getSource().getSender());
                                        return Command.SINGLE_SUCCESS;
                                    }

                                    Player target = players.getFirst();
                                    plugin.getPlayerDataManager().enable(target.getUniqueId());
                                    plugin.getRegionListener().clearPlayerRegion(target.getUniqueId());
                                    plugin.getMessageManager().sendMusicEnabledFor(ctx.getSource().getSender(), target.getName());
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                        .then(Commands.literal("off")
                                .executes(ctx -> {
                                    PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
                                    List<Player> players = resolver.resolve(ctx.getSource());

                                    if (players.isEmpty()) {
                                        plugin.getMessageManager().sendPlayerNotFound(ctx.getSource().getSender());
                                        return Command.SINGLE_SUCCESS;
                                    }

                                    Player target = players.getFirst();
                                    plugin.getPlayerDataManager().disable(target.getUniqueId());
                                    plugin.getMusicManager().stopMusic(target);
                                    plugin.getMessageManager().sendMusicDisabledFor(ctx.getSource().getSender(), target.getName());
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
                    // Self-status — player only
                    CommandSender sender = ctx.getSource().getSender();
                    if (!(sender instanceof Player player)) {
                        plugin.getMessageManager().sendRaw(sender,
                                "<#D89B6A>Usage: <#FCD472>/regionmusic status \\<player>");
                        return Command.SINGLE_SUCCESS;
                    }
                    sendStatusFor(player, player);
                    return Command.SINGLE_SUCCESS;
                })
                // Admin: status for another player (console-accessible)
                .then(Commands.argument("player", ArgumentTypes.player())
                        .requires(source -> source.getSender().hasPermission("regionmusic.admin"))
                        .executes(ctx -> {
                            PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
                            List<Player> players = resolver.resolve(ctx.getSource());

                            if (players.isEmpty()) {
                                plugin.getMessageManager().sendPlayerNotFound(ctx.getSource().getSender());
                                return Command.SINGLE_SUCCESS;
                            }

                            Player target = players.getFirst();
                            MusicManager mm = plugin.getMusicManager();
                            RegionConfig currentConfig = mm.getCurrentRegionConfig(target);

                            if (currentConfig != null) {
                                RegionTrack track = mm.getCurrentTrack(target);
                                plugin.getMessageManager().sendStatusPlayingFor(
                                        ctx.getSource().getSender(),
                                        target.getName(),
                                        currentConfig.regionId(),
                                        currentConfig.worldName(),
                                        track != null ? track.displayName() : "unknown",
                                        mm.getCurrentEffectiveVolume(target)
                                );
                            } else {
                                plugin.getMessageManager().sendStatusNotPlayingFor(ctx.getSource().getSender(), target.getName());
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
            plugin.getMessageManager().sendStatusPlaying(
                    sender,
                    currentConfig.regionId(),
                    currentConfig.worldName(),
                    track != null ? track.displayName() : "unknown",
                    mm.getCurrentEffectiveVolume(target)
            );
        } else {
            plugin.getMessageManager().sendStatusNotPlaying(sender);
        }
    }

    // --- Volume ---

    private LiteralCommandNode<CommandSourceStack> buildVolumeCommand() {
        return Commands.literal("volume")
                .requires(source -> source.getSender().hasPermission("regionmusic.volume"))
                .executes(ctx -> {
                    // Self-volume — player only
                    CommandSender sender = ctx.getSource().getSender();
                    if (!(sender instanceof Player player)) {
                        plugin.getMessageManager().sendRaw(sender,
                                "<#D89B6A>Usage: <#FCD472>/regionmusic volume \\<player> \\<0-100>");
                        return Command.SINGLE_SUCCESS;
                    }
                    int vol = plugin.getPlayerDataManager().getVolumePercent(player.getUniqueId());
                    plugin.getMessageManager().sendVolumeCurrent(player, vol);
                    return Command.SINGLE_SUCCESS;
                })
                // Self set value — player only
                .then(Commands.argument("value", IntegerArgumentType.integer(0, 100))
                        .requires(source -> source.getSender() instanceof Player)
                        .executes(ctx -> {
                            Player player = (Player) ctx.getSource().getSender();
                            int value = IntegerArgumentType.getInteger(ctx, "value");

                            plugin.getPlayerDataManager().setVolumePercent(player.getUniqueId(), value);
                            plugin.getMessageManager().sendVolumeSet(player, value);

                            // Restart music with new volume
                            plugin.getMusicManager().stopMusicSilently(player);
                            plugin.getRegionListener().clearPlayerRegion(player.getUniqueId());
                            return Command.SINGLE_SUCCESS;
                        })
                )
                // Admin: set volume for another player (console-accessible)
                .then(Commands.argument("player", ArgumentTypes.player())
                        .requires(source -> source.getSender().hasPermission("regionmusic.admin"))
                        .then(Commands.argument("value", IntegerArgumentType.integer(0, 100))
                                .executes(ctx -> {
                                    PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
                                    List<Player> players = resolver.resolve(ctx.getSource());

                                    if (players.isEmpty()) {
                                        plugin.getMessageManager().sendPlayerNotFound(ctx.getSource().getSender());
                                        return Command.SINGLE_SUCCESS;
                                    }

                                    Player target = players.getFirst();
                                    int value = IntegerArgumentType.getInteger(ctx, "value");

                                    plugin.getPlayerDataManager().setVolumePercent(target.getUniqueId(), value);
                                    plugin.getMessageManager().sendVolumeSetFor(ctx.getSource().getSender(), target.getName(), value);

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
                                plugin.getMessageManager().sendPreviewStopped(player);
                                // Clear region tracking so next check resumes region music
                                plugin.getRegionListener().clearPlayerRegion(player.getUniqueId());
                            } else {
                                plugin.getMessageManager().sendPreviewNotPlaying(player);
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(Commands.argument("sound", StringArgumentType.string())
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

    private int executePreview(Player player, String soundStr, float volume) {
        Key soundKey;
        try {
            soundKey = Key.key(soundStr);
        } catch (Exception e) {
            plugin.getMessageManager().sendError(player, "<#C27B6B>Invalid sound key: <#FCD472><sound><#C27B6B>.",
                    Placeholder.unparsed("sound", soundStr));
            return Command.SINGLE_SUCCESS;
        }

        plugin.getMusicManager().startPreview(player, soundKey, volume);
        plugin.getMessageManager().sendPreviewStarted(player, soundStr);
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
                        plugin.getMessageManager().sendListEmpty(sender);
                        return Command.SINGLE_SUCCESS;
                    }

                    // Determine current region if sender is a player
                    String currentRegionId = null;
                    String currentWorldName = null;
                    if (sender instanceof Player player) {
                        RegionConfig current = plugin.getMusicManager().getCurrentRegionConfig(player);
                        if (current != null) {
                            currentRegionId = current.regionId();
                            currentWorldName = current.worldName();
                        }
                    }

                    plugin.getMessageManager().sendListHeader(sender);

                    for (Map.Entry<String, Map<String, RegionConfig>> worldEntry : regionData.entrySet()) {
                        String worldName = worldEntry.getKey();
                        plugin.getMessageManager().sendListWorldHeader(sender, worldName);

                        for (Map.Entry<String, RegionConfig> regionEntry : worldEntry.getValue().entrySet()) {
                            String regionId = regionEntry.getKey();
                            RegionConfig config = regionEntry.getValue();
                            String sound = config.tracks().isEmpty() ? "none" : config.tracks().getFirst().displayName();

                            if (regionId.equals(currentRegionId) && worldName.equals(currentWorldName)) {
                                plugin.getMessageManager().sendListEntryCurrent(sender, regionId, sound);
                            } else {
                                plugin.getMessageManager().sendListEntry(sender, regionId, sound);
                            }
                        }
                    }

                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    // --- Help ---

    private void sendHelp(CommandSourceStack source) {
        plugin.getMessageManager().sendHelp(source.getSender());
    }

    private void sendPlayerHelp(CommandSourceStack source) {
        plugin.getMessageManager().sendPlayerHelp(source.getSender());
    }
}
