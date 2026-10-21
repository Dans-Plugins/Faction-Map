package com.dansplugins.factionmap.tiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AtomicFilesTest {
    @TempDir
    Path dir;

    @Test
    void writesANewFile() throws IOException {
        Path target = dir.resolve("index.json");
        AtomicFiles.write(target, "{}".getBytes(StandardCharsets.UTF_8));
        assertEquals("{}", Files.readString(target));
    }

    @Test
    void replacesExistingContentWhollyEvenWhenShorter() throws IOException {
        Path target = dir.resolve("t.0.0.bin.gz");
        Files.write(target, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        AtomicFiles.write(target, new byte[]{9, 9});
        assertArrayEquals(new byte[]{9, 9}, Files.readAllBytes(target));
    }

    @Test
    void writesAnEmptyFile() throws IOException {
        Path target = dir.resolve("empty");
        AtomicFiles.write(target, new byte[0]);
        assertEquals(0, Files.size(target));
    }

    @Test
    void leavesNoTemporaryFileBehind() throws IOException {
        AtomicFiles.write(dir.resolve("a"), new byte[]{1});
        AtomicFiles.write(dir.resolve("a"), new byte[]{2});
        AtomicFiles.write(dir.resolve("b"), new byte[]{3});
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of("a", "b"), files.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void aMissingParentDirectoryFailsWithoutCreatingAnything() {
        Path target = dir.resolve("missing").resolve("index.json");
        assertThrows(NoSuchFileException.class, () -> AtomicFiles.write(target, new byte[]{1}));
        assertFalse(Files.exists(dir.resolve("missing")));
    }
}
