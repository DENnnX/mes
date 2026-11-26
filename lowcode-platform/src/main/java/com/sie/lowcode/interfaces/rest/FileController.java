package com.sie.lowcode.interfaces.rest;

import com.sie.lowcode.application.IamAppService;
import com.sie.lowcode.domain.iam.UserAccount;
import com.sie.lowcode.domain.metamodel.repository.FoundationRepository;
import com.sie.lowcode.domain.shared.MetaContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 通用文件附件上传与下载控制器（服务模型 File 字段类型）。
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/files")
public class FileController {
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final FoundationRepository foundationRepo;
    private final IamAppService iamAppService;
    private final Path uploadRoot;

    public FileController(FoundationRepository foundationRepo,
                          IamAppService iamAppService,
                          @Value("${spring.datasource.url:jdbc:sqlite:./data/lowcode.db}") String datasourceUrl) throws IOException {
        this.foundationRepo = foundationRepo;
        this.iamAppService = iamAppService;
        String dbFile = datasourceUrl.replace("jdbc:sqlite:", "");
        Path parent = Paths.get(dbFile).toAbsolutePath().getParent();
        this.uploadRoot = (parent != null ? parent : Paths.get("data")).resolve("uploads");
        Files.createDirectories(this.uploadRoot);
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                                      @RequestHeader(value = "Authorization", required = false) String authHeader) throws IOException {
        UserAccount user = iamAppService.authenticate(authHeader).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录"));
        }
        String fileId = "fil_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        String original = file.getOriginalFilename() != null ? Paths.get(file.getOriginalFilename()).getFileName().toString() : "unnamed.bin";
        String storedName = fileId + "_" + original.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path target = uploadRoot.resolve(storedName);
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

        String now = TS_FMT.format(LocalDateTime.now());
        String tenantId = user.getTenantId() != null ? user.getTenantId() : MetaContext.DEFAULT_TENANT;
        foundationRepo.saveFileMeta(fileId, original, file.getContentType(), file.getSize(),
                target.toAbsolutePath().toString(), tenantId, user.getId(), now);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileId", fileId);
        body.put("name", original);
        body.put("fileName", original);
        body.put("size", file.getSize());
        body.put("contentType", file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        body.put("url", "/api/files/" + fileId);
        body.put("downloadUrl", "/api/files/" + fileId);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{fileId}")
    public ResponseEntity<Resource> download(@PathVariable("fileId") String fileId,
                                             @RequestHeader(value = "Authorization", required = false) String authHeader) {
        UserAccount user = iamAppService.authenticate(authHeader).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Optional<Map<String, Object>> metaOpt = foundationRepo.findFileMetaById(fileId);
        if (metaOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> meta = metaOpt.get();
        String fileTenant = (String) meta.get("tenant_id");
        String userTenant = Objects.requireNonNullElse(user.getTenantId(), MetaContext.DEFAULT_TENANT);
        Set<String> managedTenants = iamAppService.resolveManagedTenantIds(user);
        if (fileTenant == null || (!userTenant.equals(fileTenant)
                && managedTenants != null && !managedTenants.contains(fileTenant))) {
            return ResponseEntity.notFound().build();
        }
        Path path = Paths.get(String.valueOf(meta.get("storage_path")));
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        String fileName = String.valueOf(meta.get("file_name"));
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        String ct = meta.get("content_type") != null ? String.valueOf(meta.get("content_type")) : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(ct))
                .body(new FileSystemResource(path));
    }
}
