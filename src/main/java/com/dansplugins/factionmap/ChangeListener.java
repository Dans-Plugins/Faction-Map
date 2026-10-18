package com.dansplugins.factionmap;

import com.dansplugins.factionmap.capture.LoadedChunkReader;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.StructureGrowEvent;

/**
 * Turns world events into "this chunk may have changed". Every handler is O(1) per block, runs at
 * MONITOR after other plugins have decided, ignores cancelled events, and only records a chunk key;
 * the time spent is charged to the tick's budget.
 */
final class ChangeListener implements Listener {
    private final FactionMap plugin;
    private final LoadedChunkReader reader;

    ChangeListener(FactionMap plugin, LoadedChunkReader reader) {
        this.plugin = plugin;
        this.reader = reader;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) {
        long t = System.nanoTime();
        long k = reader.keyOf(e.getChunk());
        if (k != LoadedChunkReader.NOT_MAPPED) {
            plugin.scheduler().chunkLoaded(k);
        }
        plugin.chargeEvent(System.nanoTime() - t);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent e) {
        long t = System.nanoTime();
        long k = reader.keyOf(e.getChunk());
        if (k != LoadedChunkReader.NOT_MAPPED) {
            long pendingSince = plugin.scheduler().chunkUnloading(k);
            if (pendingSince != 0) {
                // A change is waiting for its debounce: capture it now, while the chunk is still loaded.
                plugin.captureNow(k, pendingSince);
            }
        }
        plugin.chargeEvent(System.nanoTime() - t);
    }

    private void changed(Block b) {
        long k = reader.keyOf(b);
        if (k != LoadedChunkReader.NOT_MAPPED) {
            plugin.scheduler().blockChanged(k);
        }
    }

    private void changed(BlockState s) {
        long k = reader.keyOf(s);
        if (k != LoadedChunkReader.NOT_MAPPED) {
            plugin.scheduler().blockChanged(k);
        }
    }

    private void timed(Runnable r) {
        long t = System.nanoTime();
        r.run();
        plugin.chargeEvent(System.nanoTime() - t);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        timed(() -> changed(e.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        timed(() -> changed(e.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        timed(() -> changed(e.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) {
        timed(() -> changed(e.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {
        timed(() -> changed(e.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent e) {
        timed(() -> changed(e.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        timed(() -> changed(e.getToBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        timed(() -> {
            changed(e.getBlock());
            e.getBlocks().forEach(this::changed);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        timed(() -> {
            changed(e.getBlock());
            e.getBlocks().forEach(this::changed);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        timed(() -> {
            changed(e.getBlock());
            e.blockList().forEach(this::changed);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        timed(() -> e.blockList().forEach(this::changed));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent e) {
        timed(() -> e.getBlocks().forEach(this::changed));
    }
}
