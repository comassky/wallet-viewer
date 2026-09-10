package com.comassky.wallet.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.*;

class QrGeneratorTest {

    // Public BIP86 vector, not a configured wallet address.
    private static final String ADDRESS = "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr";

    // Frozen golden output of the deterministic encoder (row-major module bits, Base64) at ECC MEDIUM.
    // Captured from output that a real QR decoder confirmed reads back to ADDRESS; guards against regressions.
    private static final int GOLDEN_SIZE = 33;
    private static final String GOLDEN_MATRIX =
            "fwD9/INsIQh2bdHR7UoTqNtVtE836BmO4F9VVX+AB7gAfbhHfW6r8o33/RaBmCXg4fp8OT07oGLf"
            + "bD4oFSdI9/y8Qs9HM36vtol6UdPE6wnhe8uXJJ2jNi/fYBXs2LWMzra2xeJbnwEK+qP/addWCVpFj9LV"
            + "rvSjq+h3f9d28JEgG1Ywfw1LhQ==";

    @Test
    void encoderMatchesGoldenMatrix() {
        QrCode qr = QrCode.encode(ADDRESS.getBytes(StandardCharsets.UTF_8), QrCode.Ecc.MEDIUM);
        assertEquals(GOLDEN_SIZE, qr.size);
        BitSet bits = new BitSet();
        int idx = 0;
        for (int r = 0; r < qr.size; r++) {
            for (int c = 0; c < qr.size; c++, idx++) {
                if (qr.getModule(c, r)) {
                    bits.set(idx);
                }
            }
        }
        assertEquals(GOLDEN_MATRIX, Base64.getEncoder().encodeToString(bits.toByteArray()));
    }

    @Test
    void svgHasWhiteBackgroundAndDarkModules() {
        for (int size : new int[]{160, 320}) {
            String svg = QrGenerator.svg(ADDRESS, size);
            assertTrue(svg.startsWith("<?xml"), "XML prolog");
            assertTrue(svg.contains("<svg "), "svg root element");
            assertTrue(svg.contains("width=\"" + size + "\"") && svg.contains("height=\"" + size + "\""), "intrinsic size");
            assertTrue(svg.contains("<rect ") && svg.contains("fill=\"#ffffff\""), "white background");
            assertTrue(svg.contains("<path ") && svg.contains("fill=\"#000000\""), "dark modules");
        }
    }

    @Test
    void invalidInputPreservesTheWrappedFailureContract() {
        for (Runnable invalid : new Runnable[]{() -> QrGenerator.svg("", 320), () -> QrGenerator.svg("address", -1)}) {
            RuntimeException failure = assertThrows(RuntimeException.class, invalid::run);
            assertEquals("QR generation failed", failure.getMessage());
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }
    }
}