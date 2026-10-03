package com.czqwq.EZMiner.chain.planning;

import java.util.concurrent.LinkedBlockingQueue;

import net.minecraft.entity.player.EntityPlayer;

import org.joml.Vector3i;

import com.czqwq.EZMiner.core.MinerConfig;
import com.czqwq.EZMiner.core.MinerModeState;
import com.czqwq.EZMiner.core.founder.BasePositionFounder;

/**
 * Assembles the planning task for a chain operation from the mode state.
 */
public class ChainPlanningRuntimeFactory {

    private final LegacyFounderPlanningFactory legacyFounderPlanningFactory = new LegacyFounderPlanningFactory();

    /**
     * Assembles a position founder for the given mode state, using the legacy founder
     * implementations from {@code core/founder/}.
     */
    public BasePositionFounder createFounderForMode(MinerModeState modeState, Vector3i center,
        LinkedBlockingQueue<Vector3i> results, EntityPlayer player, MinerConfig config) {
        return legacyFounderPlanningFactory.createFounder(modeState, center, results, player, config);
    }

    public ChainPlanningTask createTaskForMode(MinerModeState modeState, Vector3i center,
        LinkedBlockingQueue<Vector3i> results, EntityPlayer player, MinerConfig config) {
        BasePositionFounder founder = createFounderForMode(modeState, center, results, player, config);
        return new LegacyFounderPlanningTask(founder);
    }
}
