package com.example.walletviewer;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class QrGeneratorTest {
    @Test
    void pngPreservesSizeAndAddressPayload() throws Exception {
        // Public BIP86 vector, not a configured wallet address.
        String address = "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr";
        for (int size : new int[]{160, 320}) {
            byte[] png = QrGenerator.png(address, size);
            assertArrayEquals(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}, Arrays.copyOf(png, 8));
            var image = ImageIO.read(new ByteArrayInputStream(png));
            assertNotNull(image);
            assertEquals(size, image.getWidth());
            assertEquals(size, image.getHeight());
            var bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
            assertEquals(address, new MultiFormatReader().decode(bitmap).getText());
        }
    }

    @Test
    void invalidInputPreservesTheWrappedFailureContract() {
        for (Runnable invalid : new Runnable[]{() -> QrGenerator.png("", 320), () -> QrGenerator.png("address", -1)}) {
            RuntimeException failure = assertThrows(RuntimeException.class, invalid::run);
            assertEquals("QR generation failed", failure.getMessage());
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }
    }
}