package com.dansplugins.factionmap;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bundled config and how the usage-reporting block reaches a config.yml that lacks it. */
class UsageReportingTest {
    private static final String COMMAND_DISCLOSURE = "each /fmap a player runs sends one event naming the subcommand";

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
    void aKnownSubcommandIsNamedInTheEvent() {
        assertEquals(Optional.of("fmap recapture"), UsageReporting.commandEventName(true, "recapture"));
    }

    @Test
    void textThatIsNotASubcommandIsNeverReported() {
        assertEquals(Optional.empty(), UsageReporting.commandEventName(true, "steve"));
        assertEquals(Optional.empty(), UsageReporting.commandEventName(true, ""));
    }

    @Test
    void commandsThatNoPlayerRanAreNotReported() {
        assertEquals(Optional.empty(), UsageReporting.commandEventName(false, "status"));
    }

    @Test
    void theReportedSubcommandsAreTheOnesPluginYmlLists() {
        String usage = pluginYml().getString("commands.fmap.usage");
        String list = usage.substring(usage.indexOf('[') + 1, usage.indexOf(']'));
        Set<String> subcommands = Arrays.stream(list.split("\\|"))
                .map(entry -> entry.split(" ")[0])
                .collect(Collectors.toSet());
        assertEquals(subcommands, UsageReporting.SUBCOMMANDS);
    }

    @Test
    void pluginYmlListsTheJsonFormOfStats() {
        assertTrue(pluginYml().getString("commands.fmap.usage").contains("stats json"));
    }

    @Test
    void theConsoleLineDisclosesCommandEvents() {
        String notice = UsageReporting.reportingOnNotice("FactionMap");
        assertTrue(notice.startsWith("Usage reporting is on"), "smoke.yml greps the console for this prefix");
        assertTrue(notice.contains(COMMAND_DISCLOSURE), notice);
    }

    @Test
    void theConfigCommentDisclosesCommandEvents() throws IOException {
        try (InputStream in = UsageReportingTest.class.getResourceAsStream("/config.yml")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String unwrapped = text.replace("\n# ", " ");
            assertTrue(unwrapped.contains(COMMAND_DISCLOSURE), "the comment above usage-reporting");
        }
    }

    @Test
    void pluginYmlKeepsMedievalFactionsOptional() {
        YamlConfiguration p = pluginYml();
        assertEquals("FactionMap", p.getString("name"));
        assertTrue(p.getStringList("softdepend").contains("MedievalFactions"));
        assertFalse(p.isSet("depend"), "the map works without Medieval Factions");
    }

    private static YamlConfiguration pluginYml() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                UsageReportingTest.class.getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8));
    }
}
