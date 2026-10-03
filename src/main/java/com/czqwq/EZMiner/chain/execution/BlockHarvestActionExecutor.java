package com.czqwq.EZMiner.chain.execution;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

import org.joml.Vector3i;

import com.czqwq.EZMiner.compat.WitcheryVampireBridge;
import com.czqwq.EZMiner.core.founder.DeterminingIdentical;
import com.czqwq.EZMiner.mixin.interfaces.IEZMinerItemInWorldManager;

/**
 * Main-thread world mutation executor.
 *
 * <p>
 * Two code paths:
 * <ol>
 * <li><b>TE blocks</b> — vanilla {@code ItemInWorldManager.tryHarvestBlock} for
 * proper TE cleanup, BreakEvent, and neighbor notifications.</li>
 * <li><b>Non-TE blocks (fast path)</b> — the {@code ezminer$tryHarvestBlockFast}
 * Mixin, which skips per-block sound/particle packets and neighbor
 * notifications (setBlock flag 2) while keeping drops and XP.</li>
 * </ol>
 *
 * <p>
 * The batch variant that groups positions by chunk lives in
 * {@link ChunkCachedHarvester} (enabled via {@code Config.useChunkCachedHarvest}).
 *
 * @see ChunkBlockWriteHelper
 */
public class BlockHarvestActionExecutor implements ChainActionExecutor {

    @Override
    public boolean execute(Vector3i pos, EntityPlayerMP player) {
        return execute(pos, player, null);
    }

    /**
     * Per-block fast harvest with an optional neighbour-notification sink.
     *
     * @param removedSink when non-null, successfully removed non-TE blocks are
     *                    appended (see {@link ChunkCachedHarvester#harvestNext}).
     *                    {@code null} preserves the zero-overhead fast path.
     */
    public boolean execute(Vector3i pos, EntityPlayerMP player, List<ChunkBlockWriteHelper.RemovedBlock> removedSink) {
        final int x = pos.x, y = pos.y, z = pos.z;
        World world = player.worldObj;
        if (world == null) return false;

        Block block = world.getBlock(x, y, z);
        if (block == null || block.isAir(world, x, y, z)) return false;
        if (DeterminingIdentical.isUnbreakable(player, block, x, y, z)) return false;

        // ── Protection gate, ABOVE the TE/override early returns ──
        // Placed here deliberately: it must also cover the two vanilla escape branches below
        // (TE carriers and blocks that override removedByPlayer), and vanilla's
        // ItemInWorldManager.tryHarvestBlock does NOT consult canMineBlock — vanilla only checks it
        // on the dig packet, so for an automated batch removal EZMiner's check is the only gate.
        if (!world.canMineBlock(player, x, y, z)) return false;

        int meta = world.getBlockMetadata(x, y, z);

        // Blocks with tile entities must go through the vanilla path so that
        // BreakEvent fires, TE cleanup runs, and neighbor notifications reach
        // adjacent redstone/logic blocks. GT ore containers that carry a companion
        // TileEntity (legacy BlockOresAbstract / BlockOresAbstractLegacy) must also
        // stay on the vanilla path so breakBlock/removeTileEntity always run — never
        // reducing them to a stale "air + leftover metadata/TE" which renders as
        // "name.0".
        if (block.hasTileEntity(meta) || DeterminingIdentical.isGTTileEntityCarrier(block)) {
            return player.theItemInWorldManager.tryHarvestBlock(x, y, z);
        }

        // Fast path: skip playAuxSFX, excess getBlock calls, and neighbor
        // notifications (setBlock flag=2 instead of flag=3).
        //
        // No BreakEvent is pre-fired here. The mixin's own protection gate
        // (ChainBreakEventHelper.canBreakAt, called at the top of
        // ezminer$tryHarvestBlockFast) fires it when Config.fireBreakEvent is on or a
        // protection mod needs it, and the mixin computes XP itself when no event is handed in.
        // Pre-firing here as well produced TWO BreakEvents per block (the old code fired one and
        // passed it in, and the mixin fired a second inside canBreakAt).
        IEZMinerItemInWorldManager fastMgr = (IEZMinerItemInWorldManager) player.theItemInWorldManager;
        boolean canHarvest = block.canHarvestBlock(player, meta)
            || WitcheryVampireBridge.canHarvestWithBareHands(player);
        boolean removed = fastMgr.ezminer$tryHarvestBlockFast(x, y, z, canHarvest, null);
        if (removed) {
            // Tree felling: flag adjacent leaves for vanilla decay (O(1), wood only).
            ChunkBlockWriteHelper.flagNeighbouringLeavesForDecay(world, x, y, z, block);
            if (removedSink != null) {
                removedSink.add(new ChunkBlockWriteHelper.RemovedBlock(x, y, z, block));
            }
        }
        return removed;
    }
}
