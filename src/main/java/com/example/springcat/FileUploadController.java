package com.example.springcat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

@RestController
public class FileUploadController {

    private final Path uploadDir;

    public FileUploadController(@Value("${springcat.upload.dir}") String uploadDir) {
        this.uploadDir = Path.of(uploadDir);
    }

    @PostConstruct
    void init() throws Exception {
        Files.createDirectories(uploadDir);
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) throws Exception {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "file is empty"));
        }

        // Stream straight to disk so a 1.5GB upload never has to fit in memory.
        Path target = uploadDir.resolve(UUID.randomUUID() + "-" + sanitize(file.getOriginalFilename()));
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }

        return ResponseEntity.ok(Map.of(
                "storedAs", target.getFileName().toString(),
                "originalName", String.valueOf(file.getOriginalFilename()),
                "sizeBytes", file.getSize()
        ));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "file exceeds the 1.5GB upload limit"));
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "upload";
        }
        // Keep only the base file name; strip any path components a client might send.
        return Path.of(name).getFileName().toString();
    }
}
