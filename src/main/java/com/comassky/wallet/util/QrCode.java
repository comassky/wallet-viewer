/*
 * Dependency-free QR Code generator (byte mode).
 *
 * Adapted from Project Nayuki's "QR Code generator library" (MIT License).
 * Copyright (c) Project Nayuki. https://www.nayuki.io/page/qr-code-generator-library
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions: the above copyright notice and this
 * permission notice shall be included in all copies or substantial portions of the
 * Software. The Software is provided "as is", without warranty of any kind.
 */
package com.comassky.wallet.util;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;

/** Minimal QR Code encoder (byte mode) with automatic version and mask selection. */
public final class QrCode {

    /** Error correction level; higher levels tolerate more damage but hold less data. */
    public enum Ecc {
        LOW(1), MEDIUM(0), QUARTILE(3), HIGH(2);
        private final int formatBits;

        Ecc(int formatBits) {
            this.formatBits = formatBits;
        }
    }

    private static final int MIN_VERSION = 1;
    private static final int MAX_VERSION = 40;
    private static final int PENALTY_N1 = 3;
    private static final int PENALTY_N2 = 3;
    private static final int PENALTY_N3 = 40;
    private static final int PENALTY_N4 = 10;

    private static final byte[][] ECC_CODEWORDS_PER_BLOCK = {
        {-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
        {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28},
        {-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
        {-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
    };

    private static final byte[][] NUM_ERROR_CORRECTION_BLOCKS = {
        {-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25},
        {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49},
        {-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68},
        {-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81},
    };

    /** Number of modules per side of the (square) symbol. */
    public final int size;

    private final Ecc errorCorrectionLevel;
    private final int version;
    private final boolean[][] modules;
    private final boolean[][] isFunction;

    /** Encodes the given bytes as a QR Code at the given error correction level. */
    public static QrCode encode(byte[] data, Ecc ecl) {
        Objects.requireNonNull(data);
        Objects.requireNonNull(ecl);

        int version;
        for (version = MIN_VERSION; ; version++) {
            int used = 4 + charCountBits(version) + data.length * 8;
            if (used <= getNumDataCodewords(version, ecl) * 8) {
                break;
            }
            if (version >= MAX_VERSION) {
                throw new IllegalArgumentException("Data too long for a QR Code");
            }
        }

        BitBuffer bb = new BitBuffer();
        bb.appendBits(0b0100, 4); // byte-mode indicator
        bb.appendBits(data.length, charCountBits(version));
        for (byte b : data) {
            bb.appendBits(b & 0xFF, 8);
        }

        int capacityBits = getNumDataCodewords(version, ecl) * 8;
        bb.appendBits(0, Math.min(4, capacityBits - bb.length));
        bb.appendBits(0, (8 - bb.length % 8) % 8);
        for (int pad = 0xEC; bb.length < capacityBits; pad ^= 0xEC ^ 0x11) {
            bb.appendBits(pad, 8);
        }

        byte[] dataCodewords = new byte[bb.length / 8];
        for (int i = 0; i < bb.length; i++) {
            if (bb.data.get(i)) {
                dataCodewords[i >>> 3] |= (byte) (1 << (7 - (i & 7)));
            }
        }
        return new QrCode(version, ecl, dataCodewords);
    }

    /** True when the module at (x, y) is dark; out-of-bounds coordinates are light. */
    public boolean getModule(int x, int y) {
        return 0 <= x && x < size && 0 <= y && y < size && modules[y][x];
    }

    private QrCode(int version, Ecc ecl, byte[] dataCodewords) {
        this.version = version;
        this.errorCorrectionLevel = ecl;
        this.size = version * 4 + 17;
        this.modules = new boolean[size][size];
        this.isFunction = new boolean[size][size];

        drawFunctionPatterns();
        drawCodewords(addEccAndInterleave(dataCodewords));

        int mask = 0;
        int minPenalty = Integer.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            applyMask(i);
            drawFormatBits(i);
            int penalty = getPenaltyScore();
            if (penalty < minPenalty) {
                mask = i;
                minPenalty = penalty;
            }
            applyMask(i); // undo
        }
        applyMask(mask);
        drawFormatBits(mask);
    }

    // ---- data encoding -----------------------------------------------------

    private static int charCountBits(int version) {
        return version <= 9 ? 8 : 16; // byte mode
    }

    private byte[] addEccAndInterleave(byte[] data) {
        int ecl = errorCorrectionLevel.ordinal();
        int numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ecl][version];
        int blockEccLen = ECC_CODEWORDS_PER_BLOCK[ecl][version];
        int rawCodewords = getNumRawDataModules(version) / 8;
        int numShortBlocks = numBlocks - rawCodewords % numBlocks;
        int shortBlockLen = rawCodewords / numBlocks;

        byte[][] blocks = new byte[numBlocks][];
        byte[] rsDiv = reedSolomonComputeDivisor(blockEccLen);
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int datLen = shortBlockLen - blockEccLen + (i < numShortBlocks ? 0 : 1);
            byte[] dat = Arrays.copyOfRange(data, k, k + datLen);
            k += datLen;
            byte[] block = Arrays.copyOf(dat, shortBlockLen + 1);
            byte[] ecc = reedSolomonComputeRemainder(dat, rsDiv);
            System.arraycopy(ecc, 0, block, block.length - blockEccLen, blockEccLen);
            blocks[i] = block;
        }

        byte[] result = new byte[rawCodewords];
        for (int i = 0, k = 0; i < blocks[0].length; i++) {
            for (int j = 0; j < blocks.length; j++) {
                if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) {
                    result[k] = blocks[j][i];
                    k++;
                }
            }
        }
        return result;
    }

    private static int getNumDataCodewords(int version, Ecc ecl) {
        return getNumRawDataModules(version) / 8
                - ECC_CODEWORDS_PER_BLOCK[ecl.ordinal()][version] * NUM_ERROR_CORRECTION_BLOCKS[ecl.ordinal()][version];
    }

    private static int getNumRawDataModules(int version) {
        int result = (16 * version + 128) * version + 64;
        if (version >= 2) {
            int numAlign = version / 7 + 2;
            result -= (25 * numAlign - 10) * numAlign - 55;
            if (version >= 7) {
                result -= 36;
            }
        }
        return result;
    }

    // ---- Reed-Solomon ------------------------------------------------------

    private static byte[] reedSolomonComputeDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < result.length; j++) {
                result[j] = (byte) reedSolomonMultiply(result[j] & 0xFF, root);
                if (j + 1 < result.length) {
                    result[j] ^= result[j + 1];
                }
            }
            root = reedSolomonMultiply(root, 0x02);
        }
        return result;
    }

    private static byte[] reedSolomonComputeRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) {
                result[i] ^= (byte) reedSolomonMultiply(divisor[i] & 0xFF, factor);
            }
        }
        return result;
    }

    private static int reedSolomonMultiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    // ---- module drawing ----------------------------------------------------

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) {
            setFunctionModule(6, i, i % 2 == 0);
            setFunctionModule(i, 6, i % 2 == 0);
        }
        drawFinderPattern(3, 3);
        drawFinderPattern(size - 4, 3);
        drawFinderPattern(3, size - 4);

        int[] align = getAlignmentPatternPositions();
        int n = align.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (!((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0))) {
                    drawAlignmentPattern(align[i], align[j]);
                }
            }
        }
        drawFormatBits(0); // placeholder; real bits drawn after masking
        drawVersion();
    }

    private void drawFinderPattern(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int xx = x + dx;
                int yy = y + dy;
                if (0 <= xx && xx < size && 0 <= yy && yy < size) {
                    setFunctionModule(xx, yy, dist != 2 && dist != 4);
                }
            }
        }
    }

    private void drawAlignmentPattern(int x, int y) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                setFunctionModule(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
    }

    private void drawFormatBits(int mask) {
        int data = errorCorrectionLevel.formatBits << 3 | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) {
            rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        }
        int bits = (data << 10 | rem) ^ 0x5412;

        for (int i = 0; i <= 5; i++) {
            setFunctionModule(8, i, getBit(bits, i));
        }
        setFunctionModule(8, 7, getBit(bits, 6));
        setFunctionModule(8, 8, getBit(bits, 7));
        setFunctionModule(7, 8, getBit(bits, 8));
        for (int i = 9; i < 15; i++) {
            setFunctionModule(14 - i, 8, getBit(bits, i));
        }
        for (int i = 0; i < 8; i++) {
            setFunctionModule(size - 1 - i, 8, getBit(bits, i));
        }
        for (int i = 8; i < 15; i++) {
            setFunctionModule(8, size - 15 + i, getBit(bits, i));
        }
        setFunctionModule(8, size - 8, true);
    }

    private void drawVersion() {
        if (version < 7) {
            return;
        }
        int rem = version;
        for (int i = 0; i < 12; i++) {
            rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        }
        int bits = version << 12 | rem;
        for (int i = 0; i < 18; i++) {
            boolean bit = getBit(bits, i);
            int a = size - 11 + i % 3;
            int b = i / 3;
            setFunctionModule(a, b, bit);
            setFunctionModule(b, a, bit);
        }
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) {
                right = 5;
            }
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = getBit(data[i >>> 3] & 0xFF, 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean invert;
                switch (mask) {
                    case 0 -> invert = (x + y) % 2 == 0;
                    case 1 -> invert = y % 2 == 0;
                    case 2 -> invert = x % 3 == 0;
                    case 3 -> invert = (x + y) % 3 == 0;
                    case 4 -> invert = (x / 3 + y / 2) % 2 == 0;
                    case 5 -> invert = x * y % 2 + x * y % 3 == 0;
                    case 6 -> invert = (x * y % 2 + x * y % 3) % 2 == 0;
                    case 7 -> invert = ((x + y) % 2 + x * y % 3) % 2 == 0;
                    default -> throw new IllegalArgumentException();
                }
                if (!isFunction[y][x] && invert) {
                    modules[y][x] ^= true;
                }
            }
        }
    }

    private void setFunctionModule(int x, int y, boolean isDark) {
        modules[y][x] = isDark;
        isFunction[y][x] = true;
    }

    private int[] getAlignmentPatternPositions() {
        if (version == 1) {
            return new int[0];
        }
        int numAlign = version / 7 + 2;
        int step = (version == 32) ? 26
                : (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2;
        int[] result = new int[numAlign];
        result[0] = 6;
        for (int i = result.length - 1, pos = size - 7; i >= 1; i--, pos -= step) {
            result[i] = pos;
        }
        return result;
    }

    // ---- penalty scoring ---------------------------------------------------

    private int getPenaltyScore() {
        int result = 0;
        for (int y = 0; y < size; y++) {
            boolean runColor = false;
            int runX = 0;
            int[] runHistory = new int[7];
            for (int x = 0; x < size; x++) {
                if (modules[y][x] == runColor) {
                    runX++;
                    if (runX == 5) {
                        result += PENALTY_N1;
                    } else if (runX > 5) {
                        result++;
                    }
                } else {
                    finderPenaltyAddHistory(runX, runHistory);
                    if (!runColor) {
                        result += finderPenaltyCountPatterns(runHistory) * PENALTY_N3;
                    }
                    runColor = modules[y][x];
                    runX = 1;
                }
            }
            result += finderPenaltyTerminateAndCount(runColor, runX, runHistory) * PENALTY_N3;
        }
        for (int x = 0; x < size; x++) {
            boolean runColor = false;
            int runY = 0;
            int[] runHistory = new int[7];
            for (int y = 0; y < size; y++) {
                if (modules[y][x] == runColor) {
                    runY++;
                    if (runY == 5) {
                        result += PENALTY_N1;
                    } else if (runY > 5) {
                        result++;
                    }
                } else {
                    finderPenaltyAddHistory(runY, runHistory);
                    if (!runColor) {
                        result += finderPenaltyCountPatterns(runHistory) * PENALTY_N3;
                    }
                    runColor = modules[y][x];
                    runY = 1;
                }
            }
            result += finderPenaltyTerminateAndCount(runColor, runY, runHistory) * PENALTY_N3;
        }
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean color = modules[y][x];
                if (color == modules[y][x + 1] && color == modules[y + 1][x] && color == modules[y + 1][x + 1]) {
                    result += PENALTY_N2;
                }
            }
        }
        int dark = 0;
        for (boolean[] row : modules) {
            for (boolean color : row) {
                if (color) {
                    dark++;
                }
            }
        }
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        result += k * PENALTY_N4;
        return result;
    }

    private int finderPenaltyCountPatterns(int[] runHistory) {
        int n = runHistory[1];
        boolean core = n > 0 && runHistory[2] == n && runHistory[3] == n * 3 && runHistory[4] == n && runHistory[5] == n;
        return (core && runHistory[0] >= n * 4 && runHistory[6] >= n ? 1 : 0)
                + (core && runHistory[6] >= n * 4 && runHistory[0] >= n ? 1 : 0);
    }

    private int finderPenaltyTerminateAndCount(boolean currentRunColor, int currentRunLength, int[] runHistory) {
        if (currentRunColor) {
            finderPenaltyAddHistory(currentRunLength, runHistory);
            currentRunLength = 0;
        }
        currentRunLength += size;
        finderPenaltyAddHistory(currentRunLength, runHistory);
        return finderPenaltyCountPatterns(runHistory);
    }

    private void finderPenaltyAddHistory(int currentRunLength, int[] runHistory) {
        if (runHistory[0] == 0) {
            currentRunLength += size;
        }
        System.arraycopy(runHistory, 0, runHistory, 1, runHistory.length - 1);
        runHistory[0] = currentRunLength;
    }

    private static boolean getBit(int value, int i) {
        return ((value >>> i) & 1) != 0;
    }

    /** Growable bit accumulator, MSB first. */
    private static final class BitBuffer {
        private final BitSet data = new BitSet();
        private int length = 0;

        void appendBits(int value, int len) {
            for (int i = len - 1; i >= 0; i--, length++) {
                data.set(length, ((value >>> i) & 1) != 0);
            }
        }
    }
}
