package com.hcchunkspawndatafix.mixin;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/**
 * Mixin to fix ArrayIndexOutOfBoundsException in BlockChunk.loadFromHolder()
 * when loading instance worlds saved with 11 chunk sections on a server
 * that expects 10 sections (height 320).
 *
 * The vanilla code iterates column.getSectionHolders().length (which can be 11
 * from old saved data) but writes into chunkSections[] which is fixed at length 10.
 *
 * Fix: cap the loop at Math.min(sections.length, chunkSections.length).
 */
@Mixin(BlockChunk.class)
public abstract class BlockChunkMixin {

    private static final HytaleLogger LOGGER = HytaleLogger.get("HC_ChunkSpawnDataFix");
    private static boolean loggedOnce = false;

    @Shadow
    private BlockSection[] chunkSections;

    @Shadow
    private BlockSection[] migratedChunkSections;

    /**
     * @author HC_ChunkSpawnDataFix
     * @reason Fix AIOOBE when saved chunk data has more sections than current server expects
     */
    @Overwrite
    public void loadFromHolder(@Nonnull Holder<ChunkStore> holder) {
        ChunkColumn column = holder.getComponent(ChunkColumn.getComponentType());
        if (column == null) {
            return;
        }
        Holder<ChunkStore>[] sections = column.getSectionHolders();
        int limit = Math.min(sections.length, this.chunkSections.length);
        if (sections.length != this.chunkSections.length && !loggedOnce) {
            loggedOnce = true;
            LOGGER.at(Level.WARNING).log(
                "BlockChunk section count mismatch: saved=%d, expected=%d — clamping to %d (instance saved with different world height)",
                sections.length, this.chunkSections.length, limit
            );
        }
        for (int i = 0; i < limit; ++i) {
            Holder<ChunkStore> section = sections[i];
            this.chunkSections[i] = this.migratedChunkSections != null
                ? this.migratedChunkSections[i]
                : section.ensureAndGetComponent(BlockSection.getComponentType());
        }
    }
}
