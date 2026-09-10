package com.comassky.wallet.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.nio.charset.StandardCharsets;

public final class QrGenerator {

    private static final int QUIET_ZONE = 4;

    // A QR code is a pure function of its text (and size); an address's QR never changes, so memoize it.
    private static final Cache<String, String> CACHE = Caffeine.newBuilder().maximumSize(256).build();

    private QrGenerator() {
    }

    /** Self-contained SVG QR code (white background, black modules); crisp at any display size. */
    public static String svg(String text, int size) {
        try {
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException("QR text must not be empty");
            }
            if (size <= 0) {
                throw new IllegalArgumentException("QR size must be positive");
            }
            return CACHE.get(size + "\u0000" + text, key ->
                    render(QrCode.encode(text.getBytes(StandardCharsets.UTF_8), QrCode.Ecc.MEDIUM), size));
        } catch (RuntimeException e) {
            throw new RuntimeException("QR generation failed", e);
        }
    }

    private static String render(QrCode qr, int size) {
        final int modules = qr.size;
        final int dim = modules + QUIET_ZONE * 2;
        final StringBuilder path = new StringBuilder();
        for (int r = 0; r < modules; r++) {
            for (int c = 0; c < modules; c++) {
                if (qr.getModule(c, r)) {
                    path.append('M').append(c + QUIET_ZONE).append(' ').append(r + QUIET_ZONE).append("h1v1h-1z");
                }
            }
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + size + "\" height=\"" + size + "\""
                + " viewBox=\"0 0 " + dim + " " + dim + "\" shape-rendering=\"crispEdges\">"
                + "<rect width=\"" + dim + "\" height=\"" + dim + "\" fill=\"#ffffff\"/>"
                + "<path d=\"" + path + "\" fill=\"#000000\"/>"
                + "</svg>\n";
    }
}
