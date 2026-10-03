package com.czqwq.EZMiner.utils;

/**
 * Compact coordinate encoding used for visited-set and pending-lookup keys.
 *
 * <p>
 * Pure arithmetic with <strong>no Minecraft dependency</strong>, so it stays unit-testable
 * without a game environment ({@code EncodePosTest} exercises this class directly). It is
 * the single source of truth: every caller delegates here instead of carrying its own copy,
 * which is what previously let the test and the implementation drift apart silently.
 */
public final class PosEncoding {

    private PosEncoding() {}

    /**
     * Compact coordinate encoding: x/z + 30M bias in 26 bits, y in 12 bits → single long.
     * Bias avoids collisions from two's-complement negative coordinates.
     *
     * <p>
     * Note the y field is masked to 12 bits, so y outside {@code [-2048, 4095]} wraps
     * (Minecraft's build height fits comfortably inside that range).
     */
    public static long encode(int x, int y, int z) {
        return ((long) (x + 30_000_000) << 38) | ((long) (y & 0xFFF) << 26) | (long) (z + 30_000_000);
    }
}
