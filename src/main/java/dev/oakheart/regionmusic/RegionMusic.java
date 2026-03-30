package dev.oakheart.regionmusic;

import dev.oakheart.regionmusic.commands.RegionMusicCommand;
import dev.oakheart.regionmusic.config.ConfigManager;
import dev.oakheart.regionmusic.listeners.RegionMusicListener;
import dev.oakheart.message.MessageManager;
import dev.oakheart.util.DebugLogger;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class RegionMusic extends JavaPlugin {

    private static final int BSTATS_PLUGIN_ID = 28205;

    private ConfigManager configManager;
    private MusicManager musicManager;
    private RegionMusicListener regionListener;
    private PlayerDataManager playerDataManager;
    private MessageManager messageManager;
    private DebugLogger debugLogger;

    @Override
    public void onEnable() {
        try {
            checkDependencies();
            initializeComponents();
            registerListeners();
            registerCommands();
            initializeMetrics();
            registerPlaceholders();

            getLogger().info("RegionMusic enabled! Loaded " + configManager.countConfiguredRegions() + " region(s) with music.");
            if (configManager.isDebug()) {
                getLogger().info("Debug mode is enabled.");
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to enable RegionMusic", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (regionListener != null) {
            regionListener.cleanup();
        }
        if (musicManager != null) {
            musicManager.cleanup();
        }
        if (playerDataManager != null) {
            playerDataManager.saveSync();
        }
    }

    private void checkDependencies() {
        if (getServer().getPluginManager().getPlugin("WorldGuard") == null) {
            throw new IllegalStateException("WorldGuard not found! RegionMusic requires WorldGuard.");
        }
    }

    private void initializeComponents() {
        configManager = new ConfigManager(this);
        configManager.load();

        debugLogger = new DebugLogger(getLogger(), configManager::isDebug);

        messageManager = new MessageManager(this, getLogger());
        messageManager.load();

        playerDataManager = new PlayerDataManager(this);
        musicManager = new MusicManager(this);
    }

    private void registerListeners() {
        regionListener = new RegionMusicListener(this, musicManager);
        getServer().getPluginManager().registerEvents(regionListener, this);
        regionListener.startChecking();
    }

    private void registerCommands() {
        new RegionMusicCommand(this).register();
    }

    private void initializeMetrics() {
        new Metrics(this, BSTATS_PLUGIN_ID);
    }

    private void registerPlaceholders() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new RegionMusicPlaceholders(this).register();
            getLogger().info("PlaceholderAPI expansion registered.");
        }
    }

    public boolean reload() {
        if (!configManager.reload()) {
            return false;
        }

        messageManager.reload();
        playerDataManager.reload();
        musicManager.cleanup();
        regionListener.refresh();

        if (configManager.isDebug()) {
            getLogger().info("Debug mode is enabled.");
        }
        return true;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MusicManager getMusicManager() {
        return musicManager;
    }

    public PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public DebugLogger getDebugLogger() {
        return debugLogger;
    }

    public void debug(String message) {
        debugLogger.log(message);
    }

    public RegionMusicListener getRegionListener() {
        return regionListener;
    }
}
