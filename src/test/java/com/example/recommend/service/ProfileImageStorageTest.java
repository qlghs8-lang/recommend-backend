package com.example.recommend.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class ProfileImageStorageTest {
    @TempDir Path root;
    byte[] image(String format, int width, int height) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, output);
        return output.toByteArray();
    }
    @Test void acceptsRealImagesAndIgnoresUntrustedFilenameAndMime() throws Exception {
        var storage = new ProfileImageStorage(root.toString());
        for (String format : new String[]{"png", "jpg"}) {
            String url = storage.store(new MockMultipartFile("file", "../../attack.html", "text/html", image(format, 4, 4)));
            assertTrue(url.matches("/uploads/profile/[a-f0-9-]{36}\\.png"));
            Path saved = root.resolve("profile").resolve(url.substring(url.lastIndexOf('/') + 1));
            assertEquals(4, ImageIO.read(saved.toFile()).getWidth());
            storage.deleteManaged(url);
            assertFalse(Files.exists(saved));
        }
        assertTrue(storage.resourceLocation().endsWith("/profile/"));
    }
    @Test void rejectsEmptySpoofedUnsupportedAndOversizedImages() throws Exception {
        var storage = new ProfileImageStorage(root.toString());
        for (byte[] bytes : new byte[][] {new byte[0], "<script>alert(1)</script>".getBytes(), image("gif", 2, 2), image("png", 4097, 1)}) {
            assertThrows(IllegalArgumentException.class, () -> storage.store(new MockMultipartFile("file", "image.png", "image/png", bytes)));
        }
        var error = assertThrows(ResponseStatusException.class, () -> storage.store(new MockMultipartFile("file", new byte[ProfileImageStorage.MAX_BYTES + 1])));
        assertEquals(413, error.getStatusCode().value());
        assertFalse(Files.exists(root.resolve("profile")));
    }
    @Test void cleanupDoesNotDeleteDefaultLegacyOrOutsideFiles() throws Exception {
        var storage = new ProfileImageStorage(root.toString());
        Files.createDirectories(root.resolve("profile"));
        Path file = Files.writeString(root.resolve("profile/default.png"), "keep");
        storage.deleteManaged("/uploads/profile/default.png");
        storage.deleteManaged("/uploads/profile/../default.png");
        storage.deleteManaged("https://example.com/default.png");
        assertTrue(Files.exists(file));
    }
}
