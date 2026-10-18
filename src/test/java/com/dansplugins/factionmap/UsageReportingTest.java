package com.dansplugins.factionmap;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bundled config and how the usage-reporting block reaches a config.yml that lacks it. */
class UsageReportingTest {
    static YamlConfiguration bundled() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                UsageReportingTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    @Test
    void theBundledDefaultsAreThePrivacyDefaultsTheRfcStates() {
        YamlConfiguration c = bundled();
        assertTrue(c.getBoolean("factions.showNames"), "names are shown by default (owner decision 2026-10-18)");
        assertFalse(c.getBoolean("factions.showHomes"), "homes are opt-in");
        assertEquals("127.0.0.1", c.getString("web.host"), "the viewer is private to the machine by default");
        assertTrue(c.getBoolean("usage-reporting.enabled"));
        assertEquals("https://trace.danielstephenson.dev", c.getString("usage-reporting.endpoint"));
        assertEquals(500, c.getInt("capture.mainThreadBudgetMicros"));
    }

    @Test
    void theBlockIsCopiedFromTheBundledDefaultsWhenTheFileLacksIt() {
        YamlConfiguration onDisk = new YamlConfiguration();
        onDisk.set("world", "survival");
        onDisk.setDefaults(bundled());
        assertTrue(UsageReporting.ensureBlockOnDisk(onDisk));
        assertTrue(onDisk.isSet("usage-reporting.key"));
        assertEquals(true, onDisk.get("usage-reporting.enabled"));
        assertEquals("survival", onDisk.getString("world"));
    }

    @Test
    void anOperatorsOptOutIsLeftAlone() {
        YamlConfiguration onDisk = new YamlConfiguration();
        onDisk.set("usage-reporting.enabled", false);
        onDisk.setDefaults(bundled());
        assertFalse(UsageReporting.ensureBlockOnDisk(onDisk));
        assertEquals(false, onDisk.get("usage-reporting.enabled"));
    }

    @Test
    void pluginYmlKeepsMedievalFactionsOptional() {
        YamlConfiguration p = YamlConfiguration.loadConfiguration(new InputStreamReader(
                UsageReportingTest.class.getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8));
        assertEquals("FactionMap", p.getString("name"));
        assertTrue(p.getStringList("softdepend").contains("MedievalFactions"));
        assertFalse(p.isSet("depend"), "the map works without Medieval Factions");
    }
}
