package dev.oakheart.regionmusic.message;

import dev.oakheart.regionmusic.RegionMusic;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.time.Duration;

public class MessageManager {

    private final RegionMusic plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    // Display modes (cached from config)
    private String nowPlayingDisplay;
    private String musicStoppedDisplay;

    private Component prefix;
    private Component errorPrefix;
    private String configReloaded;
    private String reloadFailed;
    private String noPermission;
    private String unknownCommand;
    private String playerNotFound;
    private String nowPlaying;
    private String musicStopped;

    // Help - admin
    private String helpHeader;
    private String helpReload;
    private String helpHelp;
    private String helpStatus;
    private String helpStatusPlayer;
    private String helpToggle;
    private String helpTogglePlayer;
    private String helpVolume;
    private String helpVolumeSet;
    private String helpVolumePlayer;
    private String helpPreview;
    private String helpPreviewStop;
    private String helpList;

    // Help - player
    private String playerHelpHeader;
    private String playerHelpToggle;
    private String playerHelpStatus;
    private String playerHelpVolume;

    // Status
    private String statusPlaying;
    private String statusNotPlaying;
    private String statusPlayingFor;
    private String statusNotPlayingFor;

    // Toggle
    private String musicEnabled;
    private String musicDisabled;
    private String musicEnabledFor;
    private String musicDisabledFor;

    // Volume
    private String volumeSet;
    private String volumeCurrent;
    private String volumeSetFor;

    // Preview
    private String previewStarted;
    private String previewStopped;
    private String previewNotPlaying;

    // List
    private String listHeader;
    private String listWorldHeader;
    private String listEntry;
    private String listEntryCurrent;
    private String listEmpty;

    // Discovery
    private String regionDiscovered;

    public MessageManager(RegionMusic plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        FileConfiguration config = plugin.getConfigManager().getConfig();

        nowPlayingDisplay = plugin.getConfigManager().getNowPlayingDisplay();
        musicStoppedDisplay = plugin.getConfigManager().getMusicStoppedDisplay();

        String prefixStr = config.getString("messages.prefix", "<#6C757D>[<#6B7A5E>ᴍᴜꜱɪᴄ<#6C757D>] ");
        prefix = miniMessage.deserialize(prefixStr);

        String errorPrefixStr = config.getString("messages.error-prefix", "<#6C757D>[<#C27B6B>ᴇʀʀᴏʀ<#6C757D>] ");
        errorPrefix = miniMessage.deserialize(errorPrefixStr);

        configReloaded = config.getString("messages.config-reloaded", "<#f2ebd7>Configuration <#8FAA87>reloaded<#f2ebd7>! <#FCD472><count><#f2ebd7> region(s) loaded.");
        reloadFailed = config.getString("messages.reload-failed", "<#C27B6B>Configuration reload failed! Check console for details.");
        noPermission = config.getString("messages.no-permission", "<#C27B6B>You don't have permission to use this command.");
        unknownCommand = config.getString("messages.unknown-command", "<#C27B6B>Unknown subcommand: <#FCD472><command><#C27B6B>. Use <#FCD472>/regionmusic help<#C27B6B> for usage.");
        playerNotFound = config.getString("messages.player-not-found", "<#C27B6B>Player not found.");
        nowPlaying = config.getString("messages.now-playing", "<#f2ebd7>Now playing: <#FCD472><sound> <#6C757D>(<region>)");
        musicStopped = config.getString("messages.music-stopped", "<#f2ebd7>Music stopped.");

        helpHeader = config.getString("messages.help-header", "<#6C757D>═══════ <#6B7A5E>RegionMusic Help <#6C757D>═══════");
        helpReload = config.getString("messages.help-reload", "<#FCD472>/regionmusic reload <#6C757D>- <#f2ebd7>Reload the configuration");
        helpToggle = config.getString("messages.help-toggle", "<#FCD472>/regionmusic toggle [on|off] <#6C757D>- <#f2ebd7>Toggle music on/off");
        helpTogglePlayer = config.getString("messages.help-toggle-player", "<#FCD472>/regionmusic toggle <player> [on|off] <#6C757D>- <#f2ebd7>Toggle music for a player");
        helpStatus = config.getString("messages.help-status", "<#FCD472>/regionmusic status <#6C757D>- <#f2ebd7>Show current music status");
        helpStatusPlayer = config.getString("messages.help-status-player", "<#FCD472>/regionmusic status <player> <#6C757D>- <#f2ebd7>Show a player's music status");
        helpHelp = config.getString("messages.help-help", "<#FCD472>/regionmusic help <#6C757D>- <#f2ebd7>Show this help message");
        helpVolume = config.getString("messages.help-volume", "<#FCD472>/regionmusic volume <#6C757D>- <#f2ebd7>Show your music volume");
        helpVolumeSet = config.getString("messages.help-volume-set", "<#FCD472>/regionmusic volume <0-100> <#6C757D>- <#f2ebd7>Set your music volume");
        helpVolumePlayer = config.getString("messages.help-volume-player", "<#FCD472>/regionmusic volume <player> <0-100> <#6C757D>- <#f2ebd7>Set a player's volume");
        helpPreview = config.getString("messages.help-preview", "<#FCD472>/regionmusic preview <sound> [volume] <#6C757D>- <#f2ebd7>Preview a sound");
        helpPreviewStop = config.getString("messages.help-preview-stop", "<#FCD472>/regionmusic preview stop <#6C757D>- <#f2ebd7>Stop previewing");
        helpList = config.getString("messages.help-list", "<#FCD472>/regionmusic list <#6C757D>- <#f2ebd7>List configured regions");

        playerHelpHeader = config.getString("messages.player-help-header", "<#6C757D>═══════ <#6B7A5E>RegionMusic <#6C757D>═══════");
        playerHelpToggle = config.getString("messages.player-help-toggle", "<#FCD472>/regionmusic toggle [on|off] <#6C757D>- <#f2ebd7>Toggle music on/off");
        playerHelpStatus = config.getString("messages.player-help-status", "<#FCD472>/regionmusic status <#6C757D>- <#f2ebd7>Show current music");
        playerHelpVolume = config.getString("messages.player-help-volume", "<#FCD472>/regionmusic volume [0-100] <#6C757D>- <#f2ebd7>Show or set your volume");

        statusPlaying = config.getString("messages.status-playing", "<#f2ebd7>Currently playing: <#FCD472><sound> <#6C757D>in region <#FCD472><region> <#6C757D>(<world>)");
        statusNotPlaying = config.getString("messages.status-not-playing", "<#f2ebd7>No music currently playing.");
        statusPlayingFor = config.getString("messages.status-playing-for", "<#FCD472><player> <#f2ebd7>is listening to: <#FCD472><sound> <#6C757D>in region <#FCD472><region> <#6C757D>(<world>)");
        statusNotPlayingFor = config.getString("messages.status-not-playing-for", "<#FCD472><player> <#f2ebd7>has no music playing.");

        musicEnabled = config.getString("messages.music-enabled", "<#f2ebd7>Region music <#8FAA87>enabled<#f2ebd7>.");
        musicDisabled = config.getString("messages.music-disabled", "<#f2ebd7>Region music <#C27B6B>disabled<#f2ebd7>.");
        musicEnabledFor = config.getString("messages.music-enabled-for", "<#f2ebd7>Region music <#8FAA87>enabled<#f2ebd7> for <#FCD472><player><#f2ebd7>.");
        musicDisabledFor = config.getString("messages.music-disabled-for", "<#f2ebd7>Region music <#C27B6B>disabled<#f2ebd7> for <#FCD472><player><#f2ebd7>.");

        volumeSet = config.getString("messages.volume-set", "<#f2ebd7>Music volume set to <#FCD472><volume>%<#f2ebd7>.");
        volumeCurrent = config.getString("messages.volume-current", "<#f2ebd7>Your music volume is <#FCD472><volume>%<#f2ebd7>.");
        volumeSetFor = config.getString("messages.volume-set-for", "<#f2ebd7>Music volume set to <#FCD472><volume>%<#f2ebd7> for <#FCD472><player><#f2ebd7>.");

        previewStarted = config.getString("messages.preview-started", "<#f2ebd7>Previewing: <#FCD472><sound><#f2ebd7>.");
        previewStopped = config.getString("messages.preview-stopped", "<#f2ebd7>Preview stopped.");
        previewNotPlaying = config.getString("messages.preview-not-playing", "<#f2ebd7>No preview is playing.");

        listHeader = config.getString("messages.list-header", "<#6C757D>═══════ <#6B7A5E>Configured Regions <#6C757D>═══════");
        listWorldHeader = config.getString("messages.list-world-header", "<#FCD472><world><#6C757D>:");
        listEntry = config.getString("messages.list-entry", "  <#6C757D>- <#f2ebd7><region><#6C757D>: <#FCD472><sound>");
        listEntryCurrent = config.getString("messages.list-entry-current", "  <#8FAA87>► <#f2ebd7><region><#6C757D>: <#FCD472><sound> <#6C757D>(current)");
        listEmpty = config.getString("messages.list-empty", "<#f2ebd7>No regions configured.");

        regionDiscovered = config.getString("messages.region-discovered", "<#f2ebd7>New area discovered: <#FCD472><region><#f2ebd7>!");
    }

    // --- Send utilities ---

    public void send(CommandSender sender, String message, TagResolver... resolvers) {
        if (message == null || message.isEmpty()) return;
        Component component = prefix.append(miniMessage.deserialize(message, resolvers));
        sender.sendMessage(component);
    }

    public void sendError(CommandSender sender, String message, TagResolver... resolvers) {
        if (message == null || message.isEmpty()) return;
        Component component = errorPrefix.append(miniMessage.deserialize(message, resolvers));
        sender.sendMessage(component);
    }

    public void sendRaw(CommandSender sender, String message, TagResolver... resolvers) {
        if (message == null || message.isEmpty()) return;
        sender.sendMessage(miniMessage.deserialize(message, resolvers));
    }

    /**
     * Sends a message using the specified display mode.
     * @param mode "chat", "actionbar", "title", or "none"
     */
    public void sendDisplay(Player player, String mode, String message, TagResolver... resolvers) {
        if (message == null || message.isEmpty() || "none".equals(mode)) return;

        switch (mode) {
            case "actionbar" -> {
                Component component = miniMessage.deserialize(message, resolvers);
                player.sendActionBar(component);
            }
            case "title" -> {
                Component subtitle = miniMessage.deserialize(message, resolvers);
                Title title = Title.title(
                        Component.empty(),
                        subtitle,
                        Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(500))
                );
                player.showTitle(title);
            }
            default -> send(player, message, resolvers); // "chat" or fallback
        }
    }

    // --- Named convenience methods ---

    public void sendConfigReloaded(CommandSender sender, int count) {
        send(sender, configReloaded, Placeholder.unparsed("count", String.valueOf(count)));
    }

    public void sendReloadFailed(CommandSender sender) {
        sendError(sender, reloadFailed);
    }

    public void sendNoPermission(CommandSender sender) {
        sendError(sender, noPermission);
    }

    public void sendUnknownCommand(CommandSender sender, String command) {
        sendError(sender, unknownCommand, Placeholder.unparsed("command", command));
    }

    public void sendPlayerNotFound(CommandSender sender) {
        sendError(sender, playerNotFound);
    }

    public void sendNowPlaying(Player player, String region, String world, String sound) {
        if (nowPlaying.isEmpty()) return;
        sendDisplay(player, nowPlayingDisplay, nowPlaying,
                Placeholder.unparsed("region", region),
                Placeholder.unparsed("world", world),
                Placeholder.unparsed("sound", sound));
    }

    public void sendMusicStopped(Player player, String region, String world) {
        if (musicStopped.isEmpty()) return;
        sendDisplay(player, musicStoppedDisplay, musicStopped,
                Placeholder.unparsed("region", region),
                Placeholder.unparsed("world", world));
    }

    public void sendRegionDiscovered(Player player, String region, String world) {
        if (regionDiscovered == null || regionDiscovered.isEmpty()) return;
        send(player, regionDiscovered,
                Placeholder.unparsed("region", region),
                Placeholder.unparsed("world", world));
    }

    public void sendHelp(CommandSender sender) {
        sendRaw(sender, helpHeader);
        sendRaw(sender, helpReload);
        sendRaw(sender, helpToggle);
        sendRaw(sender, helpTogglePlayer);
        sendRaw(sender, helpStatus);
        sendRaw(sender, helpStatusPlayer);
        sendRaw(sender, helpVolume);
        sendRaw(sender, helpVolumeSet);
        sendRaw(sender, helpVolumePlayer);
        sendRaw(sender, helpPreview);
        sendRaw(sender, helpPreviewStop);
        sendRaw(sender, helpList);
        sendRaw(sender, helpHelp);
    }

    public void sendPlayerHelp(CommandSender sender) {
        sendRaw(sender, playerHelpHeader);
        sendRaw(sender, playerHelpToggle);
        sendRaw(sender, playerHelpStatus);
        sendRaw(sender, playerHelpVolume);
    }

    public void sendStatusPlaying(CommandSender sender, String region, String world, String sound, float volume) {
        send(sender, statusPlaying,
                Placeholder.unparsed("region", region),
                Placeholder.unparsed("world", world),
                Placeholder.unparsed("sound", sound),
                Placeholder.unparsed("volume", String.format("%.0f%%", volume * 100)));
    }

    public void sendStatusNotPlaying(CommandSender sender) {
        send(sender, statusNotPlaying);
    }

    public void sendStatusPlayingFor(CommandSender sender, String playerName, String region, String world, String sound, float volume) {
        send(sender, statusPlayingFor,
                Placeholder.unparsed("player", playerName),
                Placeholder.unparsed("region", region),
                Placeholder.unparsed("world", world),
                Placeholder.unparsed("sound", sound),
                Placeholder.unparsed("volume", String.format("%.0f%%", volume * 100)));
    }

    public void sendStatusNotPlayingFor(CommandSender sender, String playerName) {
        send(sender, statusNotPlayingFor, Placeholder.unparsed("player", playerName));
    }

    public void sendMusicEnabled(CommandSender sender) {
        send(sender, musicEnabled);
    }

    public void sendMusicDisabled(CommandSender sender) {
        send(sender, musicDisabled);
    }

    public void sendMusicEnabledFor(CommandSender sender, String playerName) {
        send(sender, musicEnabledFor, Placeholder.unparsed("player", playerName));
    }

    public void sendMusicDisabledFor(CommandSender sender, String playerName) {
        send(sender, musicDisabledFor, Placeholder.unparsed("player", playerName));
    }

    public void sendVolumeSet(CommandSender sender, int volume) {
        send(sender, volumeSet, Placeholder.unparsed("volume", String.valueOf(volume)));
    }

    public void sendVolumeCurrent(CommandSender sender, int volume) {
        send(sender, volumeCurrent, Placeholder.unparsed("volume", String.valueOf(volume)));
    }

    public void sendVolumeSetFor(CommandSender sender, String playerName, int volume) {
        send(sender, volumeSetFor,
                Placeholder.unparsed("player", playerName),
                Placeholder.unparsed("volume", String.valueOf(volume)));
    }

    public void sendPreviewStarted(CommandSender sender, String sound) {
        send(sender, previewStarted, Placeholder.unparsed("sound", sound));
    }

    public void sendPreviewStopped(CommandSender sender) {
        send(sender, previewStopped);
    }

    public void sendPreviewNotPlaying(CommandSender sender) {
        send(sender, previewNotPlaying);
    }

    public void sendListHeader(CommandSender sender) {
        sendRaw(sender, listHeader);
    }

    public void sendListWorldHeader(CommandSender sender, String world) {
        sendRaw(sender, listWorldHeader, Placeholder.unparsed("world", world));
    }

    public void sendListEntry(CommandSender sender, String region, String sound) {
        sendRaw(sender, listEntry,
                Placeholder.unparsed("region", region),
                Placeholder.unparsed("sound", sound));
    }

    public void sendListEntryCurrent(CommandSender sender, String region, String sound) {
        sendRaw(sender, listEntryCurrent,
                Placeholder.unparsed("region", region),
                Placeholder.unparsed("sound", sound));
    }

    public void sendListEmpty(CommandSender sender) {
        sendRaw(sender, listEmpty);
    }
}
