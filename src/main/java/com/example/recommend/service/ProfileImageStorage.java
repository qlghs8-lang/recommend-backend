package com.example.recommend.service;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class ProfileImageStorage {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private final Path directory;

    public ProfileImageStorage(@Value("${app.upload-dir:./uploads}") String root) {
        if (root.isBlank()) throw new IllegalArgumentException("Upload directory must not be blank");
        directory = Path.of(root).toAbsolutePath().normalize().resolve("profile");
    }

    public String resourceLocation() {
        String uri = directory.toUri().toString();
        return uri.endsWith("/") ? uri : uri + "/";
    }

    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) throw invalid();
        if (file.getSize() > MAX_BYTES) throw tooLarge();
        BufferedImage image;
        try (var input = file.getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw tooLarge();
            try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw invalid();
                var reader = readers.next();
                try {
                    String format = reader.getFormatName();
                    if (!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg")) throw invalid();
                    reader.setInput(stream, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || width > 4096 || height > 4096
                            || (long) width * height > 16_000_000) throw invalid();
                    image = reader.read(0);
                    if (image == null) throw invalid();
                } finally { reader.dispose(); }
            }
        } catch (IOException e) { throw invalid(); }

        // Re-encode pixels only: never retain client filename, metadata or appended payloads.
        Path target = directory.resolve(UUID.randomUUID() + ".png");
        try {
            Files.createDirectories(directory);
            try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                if (!ImageIO.write(image, "png", output)) throw new IOException("PNG writer unavailable");
            }
            return "/uploads/profile/" + target.getFileName();
        } catch (IOException e) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) { }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "이미지 저장에 실패했습니다.");
        }
    }

    public void deleteManaged(String url) {
        if (url == null || !url.matches("/uploads/profile/[0-9a-fA-F-]{36}\\.png")) return;
        try { Files.deleteIfExists(directory.resolve(url.substring(url.lastIndexOf('/') + 1))); }
        catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Profile image cleanup failed");
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("5MB 이하 JPEG/PNG 이미지(최대 4096px, 1600만 화소)를 선택해주세요.");
    }
    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "이미지는 5MB 이하여야 합니다.");
    }
}
