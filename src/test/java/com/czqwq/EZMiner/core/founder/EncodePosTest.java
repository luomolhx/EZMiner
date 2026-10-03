package com.czqwq.EZMiner.core.founder;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import com.czqwq.EZMiner.utils.PosEncoding;

/**
 * Tests for the compact coordinate encoding used as the visited-set key —
 * injectivity, boundary values, and y-masking.
 *
 * <p>
 * These tests call the <strong>real production implementation</strong>,
 * {@link PosEncoding#encode(int, int, int)}, which is deliberately free of any
 * Minecraft dependency so it can be exercised without a game environment. An earlier
 * version of this test carried an inline copy of the algorithm instead, which meant a
 * regression in the production code would not have failed the suite.
 *
 * <p>
 * The file still lives under {@code core/founder} because that is where the encoding
 * originated and where its callers sit; the class under test has since moved to
 * {@code utils}.
 */
public class EncodePosTest {

    // ── Injectivity (单射性) ─────────────────────────────────────────────────

    @Test
    public void differentCoordsProduceDifferentKeys() {
        Set<Long> seen = new HashSet<>();
        int[] values = { -100, -1, 0, 1, 100, 256, 1000, 30_000_000, -30_000_000 };
        for (int x : values) {
            for (int y : values) {
                for (int z : values) {
                    long key = PosEncoding.encode(x, y, z);
                    assertFalse("Collision at (" + x + "," + y + "," + z + ")", seen.contains(key));
                    seen.add(key);
                }
            }
        }
        assertEquals(9 * 9 * 9, seen.size());
    }

    @Test
    public void injectivityAcrossTypicalMinecraftRange() {
        Set<Long> seen = new HashSet<>();
        for (int x = -3000; x <= 3000; x += 1000) {
            for (int y = 0; y <= 255; y += 64) {
                for (int z = -3000; z <= 3000; z += 1000) {
                    long key = PosEncoding.encode(x, y, z);
                    assertFalse("Collision at (" + x + "," + y + "," + z + ")", seen.contains(key));
                    seen.add(key);
                }
            }
        }
    }

    @Test
    public void injectivityAtWorldBorder() {
        long key1 = PosEncoding.encode(29_999_999, 255, 29_999_999);
        long key2 = PosEncoding.encode(29_999_998, 255, 29_999_999);
        long key3 = PosEncoding.encode(-29_999_999, 0, -29_999_999);
        long key4 = PosEncoding.encode(-29_999_998, 0, -29_999_999);
        assertNotEquals(key1, key2);
        assertNotEquals(key3, key4);
        assertNotEquals(key1, key3);
    }

    // ── Boundary values ─────────────────────────────────────────────────────

    @Test
    public void zeroCoordinates() {
        long key = PosEncoding.encode(0, 0, 0);
        assertTrue(key >= 0);
    }

    @Test
    public void negativeCoordinatesDifferFromPositive() {
        long neg = PosEncoding.encode(-100, 64, -100);
        long pos = PosEncoding.encode(100, 64, 100);
        assertNotEquals(neg, pos);
    }

    @Test
    public void originBiasIsConsistent() {
        long key = PosEncoding.encode(-30_000_000, 0, -30_000_000);
        assertEquals(0L, key);
    }

    // ── Y-axis masking (12 bits, 0–4095) ────────────────────────────────────

    @Test
    public void yAbove4095IsMasked() {
        long key4096 = PosEncoding.encode(0, 4096, 0);
        long key0 = PosEncoding.encode(0, 0, 0);
        assertEquals("y=4096 should mask to y=0", key0, key4096);
    }

    @Test
    public void yNegativeIsMaskedBy12Bits() {
        long keyNeg1 = PosEncoding.encode(0, -1, 0);
        long key4095 = PosEncoding.encode(0, 4095, 0);
        assertEquals("y=-1 should be equivalent to y=4095 via 12-bit mask", key4095, keyNeg1);
    }

    @Test
    public void yWithinMinecraftBuildHeight() {
        Set<Long> seen = new HashSet<>();
        for (int y = 0; y <= 255; y++) {
            long key = PosEncoding.encode(0, y, 0);
            assertFalse("Collision at y=" + y, seen.contains(key));
            seen.add(key);
        }
        assertEquals(256, seen.size());
    }

    // ── X/Z beyond the 26-bit range ─────────────────────────────────────────

    @Test
    public void xAtMaxSafeRange() {
        long k1 = PosEncoding.encode(30_000_000, 64, 0);
        long k2 = PosEncoding.encode(0, 64, 30_000_000);
        assertNotEquals(k1, k2);
    }

    // ── Determinism ─────────────────────────────────────────────────────────

    @Test
    public void sameInputsProduceSameKey() {
        long k1 = PosEncoding.encode(12345, 200, -6789);
        long k2 = PosEncoding.encode(12345, 200, -6789);
        assertEquals(k1, k2);
    }
}
