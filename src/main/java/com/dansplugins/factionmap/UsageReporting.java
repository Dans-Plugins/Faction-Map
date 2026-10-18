package com.dansplugins.factionmap;

import com.dansplugins.factionmap.trace.TraceClient;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/** Fleet usage reporting (trace): loud on every start, one step to turn off. */
final class UsageReporting {
    static final String ENABLED_KEY = "usage-reporting.enabled";
    static final String ENDPOINT_KEY = "usage-reporting.endpoint";
    static final String KEY_KEY = "usage-reporting.key";
    static final String DEFAULT_ENDPOINT = "https://trace.danielstephenson.dev";

    private UsageReporting() {
    }

    /**
     * Builds the client and prints whether reporting is on. The {@code usage-reporting} block is written
     * to a config.yml that predates it, so the opt-out is always visible, and the server-wide switch in
     * {@code plugins/trace/config.yml} is created if absent and honoured.
     */
    static TraceClient start(JavaPlugin plugin) {
        if (ensureBlockOnDisk(plugin.getConfig())) {
            plugin.saveConfig();
        }
        // The one-argument getters, deliberately: they fall through to the bundled config.yml for a key
        // the file on disk lacks, where the two-argument ones would return their fallback instead.
        FileConfiguration config = plugin.getConfig();
        String endpoint = config.getString(ENDPOINT_KEY);
        String key = config.getString(KEY_KEY);
        TraceClient trace = TraceClient.builder(endpoint != null ? endpoint : DEFAULT_ENDPOINT, plugin.getName(),
                        plugin.getDescription().getVersion())
                .key(key != null ? key : "")
                .enabled(config.getBoolean(ENABLED_KEY))
                .serverWideConfig(plugin.getDataFolder().getParentFile())
                .logger(plugin.getLogger())
                .build();
        if (trace.isEnabled()) {
            plugin.getLogger().info("Usage reporting is on: " + plugin.getName() + " sends its name and version to"
                    + " https://trace.danielstephenson.dev, plus a random server ID (server-id in plugins/trace/config.yml)"
                    + " - nothing about players. Turn it off with usage-reporting.enabled: false in this plugin's"
                    + " config.yml, or for every plugin with enabled: false in plugins/trace/config.yml."
                    + " Details: https://danielstephenson.dev/usage-reporting");
        } else {
            plugin.getLogger().info("Usage reporting is off (" + trace.disabledReason() + ").");
        }
        return trace;
    }

    /**
     * Copies the {@code usage-reporting} block from the bundled defaults into a configuration that lacks
     * it. {@code isSet} is deliberate: unlike {@code contains} it ignores the registered defaults.
     *
     * @return true when the block was added and the configuration needs saving
     */
    static boolean ensureBlockOnDisk(FileConfiguration config) {
        if (config.isSet("usage-reporting")) {
            return false;
        }
        Configuration defaults = config.getDefaults();
        if (defaults == null) {
            return false;
        }
        for (String key : List.of(ENABLED_KEY, ENDPOINT_KEY, KEY_KEY)) {
            config.set(key, defaults.get(key));
        }
        return true;
    }
}
