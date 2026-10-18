package com.dansplugins.factionmap.tiles;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Writes a file so that a reader sees either the old content or the new, never a partial file. */
public final class AtomicFiles {
    private AtomicFiles() {
    }

    public static void write(Path target, byte[] data) throws IOException {
        Path tmp = target.resolveSibling(".tmp-" + target.getFileName());
        Files.write(tmp, data);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
