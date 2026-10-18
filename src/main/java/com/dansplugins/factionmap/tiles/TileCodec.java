package com.dansplugins.factionmap.tiles;

import com.dansplugins.factionmap.mesh.Mesher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * The tile format the viewer reads, from the KFE map prototype ("KFT1"), little-endian:
 *
 * <pre>
 *   "KFT1"  int32 quadCount  int32 tileX  int32 tileZ
 *   uint8  dir[quadCount]   (+1 pad byte when quadCount is odd)
 *   int16  x[quadCount]  z[quadCount]  y0[quadCount]  du[quadCount]  dv[quadCount]  colour[quadCount]
 * </pre>
 *
 * x and z are relative to the tile's north-west corner ({@code tileX * 128}, {@code tileZ * 128}).
 * The whole thing is gzipped. Java's gzip header carries no timestamp, so equal input gives equal bytes.
 */
public final class TileCodec {
    private TileCodec() {
    }

    /** One chunk's mesh, in chunk-local coordinates, and the chunk's offset inside the tile in chunks. */
    public record Part(int chunkOffsetX, int chunkOffsetZ, short[] mesh) {
    }

    public static byte[] encode(int tileX, int tileZ, List<Part> parts) {
        int n = 0;
        for (Part p : parts) {
            n += p.mesh().length / Mesher.STRIDE;
        }
        ByteBuffer bb = ByteBuffer.allocate(16 + n + (n % 2) + 12 * n).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(new byte[]{'K', 'F', 'T', '1'}).putInt(n).putInt(tileX).putInt(tileZ);
        for (Part p : parts) {
            short[] m = p.mesh();
            for (int q = 0; q < m.length; q += Mesher.STRIDE) {
                bb.put((byte) m[q]);
            }
        }
        if (n % 2 == 1) {
            bb.put((byte) 0);
        }
        for (int col = 1; col < Mesher.STRIDE; col++) {
            for (Part p : parts) {
                short[] m = p.mesh();
                int ox = p.chunkOffsetX() * 16;
                int oz = p.chunkOffsetZ() * 16;
                for (int q = 0; q < m.length; q += Mesher.STRIDE) {
                    int v = m[q + col];
                    if (col == 1) {
                        v += ox;
                    } else if (col == 2) {
                        v += oz;
                    }
                    bb.putShort((short) v);
                }
            }
        }
        return bb.array();
    }

    /** Tiles: level 6, the best size for the time (level 9 is ~2% smaller and ~8x slower on real tiles). */
    public static byte[] gzip(byte[] raw) {
        return gzip(raw, 6);
    }

    public static byte[] gzip(byte[] raw, int level) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
        try (GZIPOutputStream gz = new GZIPOutputStream(bos, 65536) {
            {
                def.setLevel(level);
            }
        }) {
            gz.write(raw);
        } catch (IOException e) {
            throw new IllegalStateException("in-memory gzip failed", e);
        }
        return bos.toByteArray();
    }
}
