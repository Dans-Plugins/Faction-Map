package com.dansplugins.factionmap.surface;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaletteTest {
    private static InputStream table() {
        return new ByteArrayInputStream("{\"STONE\": \"#7d7d7d\", \"GRASS_BLOCK\": \"#5d9b3a\"}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void coloursComeFromTheTableAndIndicesAreAssignedInOrder() throws IOException {
        Palette p = new Palette(table());
        assertEquals(0, p.indexOf("minecraft:stone", "STONE"));
        assertEquals(1, p.indexOf("minecraft:grass_block", "GRASS_BLOCK"));
        assertEquals(0, p.indexOf("minecraft:stone", "STONE"));
        assertEquals(List.of("#7d7d7d", "#5d9b3a"), p.colours());
    }

    @Test
    void anUnknownBlockGetsAStableFallbackColour() throws IOException {
        Palette p = new Palette(table());
        int i = p.indexOf("minecraft:mystery_block", "MYSTERY_BLOCK");
        String c = p.colours().get(i);
        assertTrue(c.matches("#[0-9a-f]{6}"), c);
        assertEquals(c, Palette.fallbackColour("MYSTERY_BLOCK"));
    }

    @Test
    void indicesSurviveARestartWhateverOrderBlocksAreSeenIn(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("palette.txt");
        Palette first = new Palette(table());
        first.bind(file);
        first.indexOf("minecraft:grass_block", "GRASS_BLOCK");
        first.indexOf("minecraft:stone", "STONE");
        first.saveIfGrown();

        Palette second = new Palette(table());
        second.bind(file);
        assertEquals(1, second.indexOf("minecraft:stone", "STONE"), "stone keeps the index baked into old tiles");
        assertEquals(0, second.indexOf("minecraft:grass_block", "GRASS_BLOCK"));
        assertEquals(first.colours(), second.colours());
        assertEquals(2, second.indexOf("minecraft:dirt", "DIRT"), "new blocks are appended");
    }

    @Test
    void saveIsSkippedWhenNothingWasAddedAndBadLinesAreIgnored(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("palette.txt");
        Files.writeString(file, "minecraft:stone #7d7d7d\ngarbage\nminecraft:x notacolour\nminecraft:stone #000000\n");
        Palette p = new Palette(table());
        p.bind(file);
        assertEquals(1, p.size());
        p.saveIfGrown();
        assertTrue(Files.readString(file).contains("garbage"), "an unchanged palette is not rewritten");
    }
}
