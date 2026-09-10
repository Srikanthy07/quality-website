package com.qualitywebsite.service;

import com.qualitywebsite.entity.DocumentEntity;
import com.qualitywebsite.entity.DocumentMaster;
import com.qualitywebsite.entity.DocumentVersion;
import com.qualitywebsite.repository.DocumentMasterRepository;
import com.qualitywebsite.repository.DocumentRepository;
import com.qualitywebsite.repository.DocumentVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class DmsMigrationService {

    public enum MigrationOutcome {
        CREATED,
        RESTORED,
        ALREADY_VALID,
        FILE_NOT_FOUND,
        SKIPPED_DELETED
    }

    public record MigrationResult(DocumentMaster master, MigrationOutcome outcome) {}

    private final DocumentRepository documentRepository;
    private final DocumentMasterRepository documentMasterRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final com.qualitywebsite.repository.DeletedDocumentRepository deletedDocumentRepository;
    private final DmsDocumentService dmsDocumentService;
    private final ResourceLoader resourceLoader;

    @Value("${app.upload.dir:./uploaded-documents}")
    private String uploadDir;

    @EventListener(ApplicationReadyEvent.class)
    @Order(2) // Run after DataInitializationService
    @Transactional
    public void migrateToDatabaseStorage() {
        log.info("[DMS Migration] Synchronizing legacy document entities into MySQL LONGBLOB DMS storage...");

        List<DocumentEntity> existingEntities = documentRepository.findAll();
        int newMigrations = 0;
        int restored = 0;
        int alreadyValid = 0;
        int failures = 0;

        for (DocumentEntity legacy : existingEntities) {
            try {
                MigrationResult result = migrateSingleDocumentWithResult(legacy, null);
                if (result != null && result.outcome() != null) {
                    switch (result.outcome()) {
                        case CREATED -> newMigrations++;
                        case RESTORED -> restored++;
                        case ALREADY_VALID -> alreadyValid++;
                        case FILE_NOT_FOUND, SKIPPED_DELETED -> {}
                    }
                }
            } catch (Exception e) {
                failures++;
                log.error("[DMS Migration] Failed to migrate document {}: {}", legacy.getId(), e.getMessage(), e);
            }
        }

        log.info("===============================================================");
        log.info("[DMS Migration] COMPLETED. New migrations: {}, restored: {}, already valid: {}, failures: {}",
                newMigrations, restored, alreadyValid, failures);
        log.info("===============================================================");
    }

    @Transactional
    public DocumentMaster migrateSingleDocument(DocumentEntity legacy, byte[] fileBytes) {
        MigrationResult result = migrateSingleDocumentWithResult(legacy, fileBytes);
        return result != null ? result.master() : null;
    }

    @Transactional
    public MigrationResult migrateSingleDocumentWithResult(DocumentEntity legacy, byte[] fileBytes) {
        if (legacy == null) return new MigrationResult(null, MigrationOutcome.FILE_NOT_FOUND);

        String code = (legacy.getId() != null && !legacy.getId().isBlank()) ? legacy.getId().trim() : (legacy.getProcess() + "-" + legacy.getFileName());
        String targetFileName = legacy.getFileName() != null ? legacy.getFileName() : (legacy.getDocumentName() + "." + getExtension(legacy.getFileName()).toLowerCase(Locale.ROOT));

        // Deterministic Document Identity Resolution Priority:
        // 1. Explicit DMS master reference (e.g. "DMS-123")
        Optional<DocumentMaster> existingMasterOpt = Optional.empty();
        if (legacy.getId() != null && legacy.getId().startsWith("DMS-")) {
            try {
                Long mId = Long.parseLong(legacy.getId().substring(4));
                existingMasterOpt = documentMasterRepository.findById(mId);
            } catch (NumberFormatException ignored) {}
        }

        // 2. Stable documentCode lookup
        if (existingMasterOpt.isEmpty() && code != null && !code.isBlank()) {
            existingMasterOpt = documentMasterRepository.findByDocumentCode(code);
        }

        // 3. Compound identity: process + category + documentName
        if (existingMasterOpt.isEmpty() && legacy.getProcess() != null && legacy.getCategory() != null && legacy.getDocumentName() != null) {
            existingMasterOpt = documentMasterRepository.findByProcessIdIgnoreCaseAndCategoryIgnoreCaseAndDocumentNameIgnoreCase(
                    legacy.getProcess().trim(), legacy.getCategory().trim(), legacy.getDocumentName().trim());
        }

        // Check if this document code was previously archived in deleted_documents
        if (deletedDocumentRepository.findByDocumentCode(code).isPresent()) {
            log.info("[MIGRATION] legacyId={} masterId=N/A code={} process={} category={} docName='{}' filename='{}' action=SKIPPED_DUPLICATE reason='Document was previously deleted and archived'",
                    legacy.getId(), code, legacy.getProcess(), legacy.getCategory(), legacy.getDocumentName(), targetFileName);
            return new MigrationResult(null, MigrationOutcome.SKIPPED_DELETED);
        }

        if (existingMasterOpt.isPresent()) {
            DocumentMaster master = existingMasterOpt.get();
            String legVer = legacy.getVersion() != null ? legacy.getVersion() : "1.0";

            if (!"ARCHIVED".equalsIgnoreCase(master.getStatus()) && !"DELETED".equalsIgnoreCase(master.getStatus())) {
                if (!legVer.equals(master.getCurrentVersion())) {
                    master.setCurrentVersion(legVer);
                    documentMasterRepository.save(master);

                    int[] parts = parseVersion(legVer);
                    Optional<DocumentVersion> latestVerOpt = documentVersionRepository.findByDocumentMasterIdAndIsLatestTrue(master.getId());
                    if (latestVerOpt.isPresent()) {
                        DocumentVersion dv = latestVerOpt.get();
                        dv.setMajorVersion(parts[0]);
                        dv.setMinorVersion(parts[1]);
                        dv.setVersion(legVer);
                        documentVersionRepository.save(dv);
                    }
                }
            }

            // Inspect latest DocumentVersion
            Optional<DocumentVersion> latestOpt = documentVersionRepository.findByDocumentMasterIdAndIsLatestTrue(master.getId());
            if (latestOpt.isEmpty()) {
                List<DocumentVersion> allVersions = documentVersionRepository.findByDocumentMasterIdOrderByUploadedDateDesc(master.getId());
                if (!allVersions.isEmpty()) {
                    latestOpt = Optional.of(allVersions.get(0));
                }
            }

            if (latestOpt.isPresent()) {
                DocumentVersion version = latestOpt.get();
                if (version.getFileData() != null && version.getFileData().length > 0) {
                    log.info("[DMS Migration] Existing DMS document with valid fileData: docCode={}, masterId={}, versionId={}, size={}",
                            master.getDocumentCode(), master.getId(), version.getId(), version.getFileData().length);
                    return new MigrationResult(master, MigrationOutcome.ALREADY_VALID);
                }

                // fileData is null or empty, restore from physical file
                byte[] bytes = (fileBytes != null && fileBytes.length > 0) ? fileBytes : readPhysicalFileBytes(legacy.getFilePath());
                if (bytes != null && bytes.length > 0) {
                    String checksum = dmsDocumentService.calculateChecksum(bytes);
                    String fileExt = legacy.getFileType() != null ? legacy.getFileType().toUpperCase(Locale.ROOT) : getExtension(legacy.getFileName());
                    version.setFileData(bytes);
                    version.setFileSize((long) bytes.length);
                    version.setChecksum(checksum);
                    if (version.getFileName() == null || version.getFileName().isBlank()) {
                        version.setFileName(legacy.getFileName() != null ? legacy.getFileName() : (master.getDocumentName() + "." + fileExt.toLowerCase(Locale.ROOT)));
                    }
                    if (version.getFileType() == null || version.getFileType().isBlank()) {
                        version.setFileType(fileExt);
                    }
                    documentVersionRepository.save(version);
                    log.info("[DMS Migration] Restored missing fileData: docCode={}, masterId={}, versionId={}, size={}",
                            master.getDocumentCode(), master.getId(), version.getId(), bytes.length);
                    return new MigrationResult(master, MigrationOutcome.RESTORED);
                } else {
                    log.warn("[DMS Migration] Physical file not found: docCode={}, filePath={}", master.getDocumentCode(), legacy.getFilePath());
                    return new MigrationResult(master, MigrationOutcome.FILE_NOT_FOUND);
                }
            } else {
                // Master exists but no DocumentVersion row exists at all
                byte[] bytes = (fileBytes != null && fileBytes.length > 0) ? fileBytes : readPhysicalFileBytes(legacy.getFilePath());
                if (bytes != null && bytes.length > 0) {
                    String checksum = dmsDocumentService.calculateChecksum(bytes);
                    String fileExt = legacy.getFileType() != null ? legacy.getFileType().toUpperCase(Locale.ROOT) : getExtension(legacy.getFileName());
                    int[] verParts = parseVersion(legVer);
                    DocumentVersion version = DocumentVersion.builder()
                            .documentMaster(master)
                            .version(legVer)
                            .majorVersion(verParts[0])
                            .minorVersion(verParts[1])
                            .fileName(legacy.getFileName() != null ? legacy.getFileName() : (master.getDocumentName() + "." + fileExt.toLowerCase(Locale.ROOT)))
                            .fileType(fileExt)
                            .fileSize((long) bytes.length)
                            .fileData(bytes)
                            .checksum(checksum)
                            .uploadedBy("migration")
                            .uploadedDate(legacy.getCreatedAt() != null ? legacy.getCreatedAt() : LocalDateTime.now())
                            .approvedBy("system")
                            .approvedDate(legacy.getCreatedAt() != null ? legacy.getCreatedAt() : LocalDateTime.now())
                            .approvalStatus("APPROVED")
                            .remarks("Migrated from filesystem storage")
                            .isLatest(true)
                            .build();
                    documentVersionRepository.save(version);
                    log.info("[DMS Migration] Restored missing fileData: docCode={}, masterId={}, created new version with size={}",
                            master.getDocumentCode(), master.getId(), bytes.length);
                    return new MigrationResult(master, MigrationOutcome.RESTORED);
                } else {
                    log.warn("[DMS Migration] Physical file not found: docCode={}, filePath={}", master.getDocumentCode(), legacy.getFilePath());
                    return new MigrationResult(master, MigrationOutcome.FILE_NOT_FOUND);
                }
            }
        }

        // New DocumentMaster creation
        byte[] bytes = (fileBytes != null && fileBytes.length > 0) ? fileBytes : readPhysicalFileBytes(legacy.getFilePath());
        if (bytes == null || bytes.length == 0) {
            log.warn("[DMS Migration] Physical file not found: legacyId={}, filePath={}", legacy.getId(), legacy.getFilePath());
            return new MigrationResult(null, MigrationOutcome.FILE_NOT_FOUND);
        }

        String checksum = dmsDocumentService.calculateChecksum(bytes);
        String fileExt = legacy.getFileType() != null ? legacy.getFileType().toUpperCase(Locale.ROOT) : getExtension(legacy.getFileName());

        String processId = (legacy.getProcess() != null && !legacy.getProcess().isEmpty()) ? legacy.getProcess() : "GLOBAL";
        String category = (legacy.getCategory() != null && !legacy.getCategory().isEmpty()) ? legacy.getCategory() : "ASPICE PRM";
        String docName = (legacy.getDocumentName() != null && !legacy.getDocumentName().isEmpty()) ? legacy.getDocumentName() : "Document";
        String legVer = legacy.getVersion() != null ? legacy.getVersion() : "1.0";
        int[] verParts = parseVersion(legVer);

        DocumentMaster master = DocumentMaster.builder()
                .documentCode(code)
                .processId(processId)
                .processName(processId)
                .processGroup(legacy.getProcessGroup() != null ? legacy.getProcessGroup() : "General")
                .category(category)
                .documentName(docName)
                .description(legacy.getDescription())
                .currentVersion(legVer)
                .status("APPROVED")
                .createdBy(legacy.getCreatedBy() != null ? legacy.getCreatedBy() : "system")
                .createdDate(legacy.getCreatedAt() != null ? legacy.getCreatedAt() : LocalDateTime.now())
                .updatedDate(legacy.getUpdatedAt() != null ? legacy.getUpdatedAt() : LocalDateTime.now())
                .build();

        master = documentMasterRepository.save(master);

        DocumentVersion version = DocumentVersion.builder()
                .documentMaster(master)
                .version(legVer)
                .majorVersion(verParts[0])
                .minorVersion(verParts[1])
                .fileName(legacy.getFileName() != null ? legacy.getFileName() : (docName + "." + fileExt.toLowerCase(Locale.ROOT)))
                .fileType(fileExt)
                .fileSize((long) bytes.length)
                .fileData(bytes)
                .checksum(checksum)
                .uploadedBy("migration")
                .uploadedDate(legacy.getCreatedAt() != null ? legacy.getCreatedAt() : LocalDateTime.now())
                .approvedBy("system")
                .approvedDate(legacy.getCreatedAt() != null ? legacy.getCreatedAt() : LocalDateTime.now())
                .approvalStatus("APPROVED")
                .remarks("Migrated from filesystem storage")
                .isLatest(true)
                .build();

        documentVersionRepository.save(version);

        dmsDocumentService.logActivity(master.getId(), master.getCurrentVersion(), "MIGRATION", "system", "Migrated legacy document " + legacy.getId() + " to LONGBLOB storage");

        log.info("[DMS Migration] Migrated new document: docCode={}, masterId={}, versionId={}, size={}",
                master.getDocumentCode(), master.getId(), version.getId(), bytes.length);

        return new MigrationResult(master, MigrationOutcome.CREATED);
    }

    public byte[] readPhysicalFileBytes(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) return null;

        String cleanPath = filePath.trim().replaceAll("^/+", "").replaceAll("\\\\", "/");

        // 1. Try resolving in uploaded-documents directory
        if (cleanPath.startsWith("uploaded-documents/")) {
            String subName = cleanPath.substring("uploaded-documents/".length());
            Path p = Paths.get(uploadDir).resolve(subName);
            if (Files.exists(p) && Files.isReadable(p)) {
                try {
                    return Files.readAllBytes(p);
                } catch (IOException ignored) {}
            }
        }

        // 2. Map legacy path aliases to canonical project static document path
        // e.g. documents/manual/...   -> documents/aspice-prm/MAN/...
        //      documents/supplier/... -> documents/aspice-prm/SUP/...
        //      documents/software/... -> documents/aspice-prm/SWE/...
        //      documents/system/...   -> documents/aspice-prm/SYS/...
        List<String> candidatePaths = new ArrayList<>();
        candidatePaths.add(cleanPath);

        String mappedPath = mapLegacyAlias(cleanPath);
        if (!mappedPath.equals(cleanPath)) {
            candidatePaths.add(mappedPath);
        }

        // 3. Try resolving candidates directly via ResourceLoader & File system
        for (String candidate : candidatePaths) {
            byte[] bytes = tryReadClasspathOrFile(candidate);
            if (bytes != null && bytes.length > 0) {
                return bytes;
            }
        }

        // 4. Case-insensitive & relative path resolution against static documents
        // Handles case mismatch on Linux (ext4) where legacy DB paths or JSON metadata may be lowercased
        byte[] bytes = resolveCaseInsensitive(cleanPath);
        if (bytes != null && bytes.length > 0) {
            return bytes;
        }

        if (!mappedPath.equals(cleanPath)) {
            bytes = resolveCaseInsensitive(mappedPath);
            if (bytes != null && bytes.length > 0) {
                return bytes;
            }
        }

        return null;
    }

    private String mapLegacyAlias(String path) {
        if (path == null) return "";
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.startsWith("documents/manual/")) {
            return "documents/aspice-prm/MAN/" + path.substring("documents/manual/".length());
        }
        if (lower.startsWith("documents/supplier/")) {
            return "documents/aspice-prm/SUP/" + path.substring("documents/supplier/".length());
        }
        if (lower.startsWith("documents/software/")) {
            return "documents/aspice-prm/SWE/" + path.substring("documents/software/".length());
        }
        if (lower.startsWith("documents/system/")) {
            return "documents/aspice-prm/SYS/" + path.substring("documents/system/".length());
        }
        return path;
    }

    private byte[] tryReadClasspathOrFile(String path) {
        if (path == null || path.isBlank()) return null;

        String subPath = path.startsWith("static/") ? path.substring("static/".length()) : path;

        // Try via Spring ResourceLoader
        try {
            Resource res = resourceLoader.getResource("classpath:static/" + subPath);
            if (res.exists() && res.isReadable()) {
                try (InputStream is = res.getInputStream()) {
                    return is.readAllBytes();
                }
            }
        } catch (Exception ignored) {}

        // Try via direct src/main/resources/static/
        try {
            Path directPath = Paths.get("src/main/resources/static/" + subPath);
            if (Files.exists(directPath) && Files.isReadable(directPath)) {
                return Files.readAllBytes(directPath);
            }
        } catch (Exception ignored) {}

        // Try direct file path
        try {
            Path fileP = Paths.get(path);
            if (Files.exists(fileP) && Files.isReadable(fileP)) {
                return Files.readAllBytes(fileP);
            }
        } catch (Exception ignored) {}

        return null;
    }

    private final Map<String, Object> staticDocumentIndex = new ConcurrentHashMap<>();
    private volatile boolean staticIndexInitialized = false;

    private void ensureStaticDocumentIndex() {
        if (staticIndexInitialized) return;
        synchronized (staticDocumentIndex) {
            if (staticIndexInitialized) return;

            Set<String> filenamesSeen = new HashSet<>();
            Set<String> ambiguousFilenames = new HashSet<>();

            List<Path> searchRoots = new ArrayList<>();
            try {
                Resource res = resourceLoader.getResource("classpath:static/documents");
                if (res.exists()) {
                    try {
                        Path p = Paths.get(res.getURI());
                        if (Files.isDirectory(p)) {
                            searchRoots.add(p);
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}

            Path srcRoot = Paths.get("src/main/resources/static/documents");
            if (Files.isDirectory(srcRoot) && !searchRoots.contains(srcRoot)) {
                searchRoots.add(srcRoot);
            }

            for (Path root : searchRoots) {
                try (var stream = Files.walk(root)) {
                    stream.filter(Files::isRegularFile).forEach(file -> {
                        Path staticDir = root.getParent();
                        if (staticDir != null) {
                            String rel = staticDir.relativize(file).toString().replaceAll("\\\\", "/");
                            String norm = rel.toLowerCase(Locale.ROOT);
                            staticDocumentIndex.putIfAbsent(norm, file);

                            if (norm.startsWith("documents/aspice-prm/man/")) {
                                staticDocumentIndex.putIfAbsent("documents/manual/" + norm.substring("documents/aspice-prm/man/".length()), file);
                            } else if (norm.startsWith("documents/aspice-prm/sup/")) {
                                staticDocumentIndex.putIfAbsent("documents/supplier/" + norm.substring("documents/aspice-prm/sup/".length()), file);
                            } else if (norm.startsWith("documents/aspice-prm/swe/")) {
                                staticDocumentIndex.putIfAbsent("documents/software/" + norm.substring("documents/aspice-prm/swe/".length()), file);
                            } else if (norm.startsWith("documents/aspice-prm/sys/")) {
                                staticDocumentIndex.putIfAbsent("documents/system/" + norm.substring("documents/aspice-prm/sys/".length()), file);
                            }

                            String fn = file.getFileName().toString().toLowerCase(Locale.ROOT);
                            if (!filenamesSeen.add(fn)) {
                                ambiguousFilenames.add(fn);
                            } else {
                                staticDocumentIndex.putIfAbsent(fn, file);
                            }
                        }
                    });
                } catch (Exception e) {
                    log.warn("[DMS Migration] Error scanning directory {}: {}", root, e.getMessage());
                }
            }

            // Remove ambiguous filenames so we never match the wrong file
            for (String amb : ambiguousFilenames) {
                staticDocumentIndex.remove(amb);
            }

            // If classpath scanning via ResourcePatternResolver is also possible (e.g. inside JAR)
            try {
                ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(resourceLoader);
                Resource[] resources = resolver.getResources("classpath*:static/documents/**/*.*");
                for (Resource r : resources) {
                    if (r.isReadable()) {
                        try {
                            String desc = r.getURI().toString().replaceAll("\\\\", "/");
                            int idx = desc.indexOf("static/documents");
                            if (idx >= 0) {
                                String rel = desc.substring(idx + "static/".length());
                                String norm = rel.toLowerCase(Locale.ROOT);
                                staticDocumentIndex.putIfAbsent(norm, r);

                                if (norm.startsWith("documents/aspice-prm/man/")) {
                                    staticDocumentIndex.putIfAbsent("documents/manual/" + norm.substring("documents/aspice-prm/man/".length()), r);
                                } else if (norm.startsWith("documents/aspice-prm/sup/")) {
                                    staticDocumentIndex.putIfAbsent("documents/supplier/" + norm.substring("documents/aspice-prm/sup/".length()), r);
                                } else if (norm.startsWith("documents/aspice-prm/swe/")) {
                                    staticDocumentIndex.putIfAbsent("documents/software/" + norm.substring("documents/aspice-prm/swe/".length()), r);
                                } else if (norm.startsWith("documents/aspice-prm/sys/")) {
                                    staticDocumentIndex.putIfAbsent("documents/system/" + norm.substring("documents/aspice-prm/sys/".length()), r);
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}

            staticIndexInitialized = true;
        }
    }

    private byte[] resolveCaseInsensitive(String targetPath) {
        if (targetPath == null || targetPath.isBlank()) return null;
        ensureStaticDocumentIndex();

        String norm = targetPath.trim().replaceAll("^/+", "").replaceAll("\\\\", "/").toLowerCase(Locale.ROOT);
        if (norm.startsWith("static/")) {
            norm = norm.substring("static/".length());
        }

        Object match = staticDocumentIndex.get(norm);
        if (match == null && !norm.startsWith("documents/")) {
            match = staticDocumentIndex.get("documents/" + norm);
        }
        if (match == null) {
            String fileName = Paths.get(norm).getFileName().toString();
            match = staticDocumentIndex.get(fileName);
        }

        if (match instanceof Path p) {
            try {
                return Files.readAllBytes(p);
            } catch (IOException ignored) {}
        } else if (match instanceof Resource r) {
            try (InputStream is = r.getInputStream()) {
                return is.readAllBytes();
            } catch (IOException ignored) {}
        }

        return null;
    }

    private int[] parseVersion(String versionStr) {
        if (versionStr == null || versionStr.trim().isEmpty()) {
            return new int[]{1, 0};
        }
        String trimmed = versionStr.trim();
        String[] parts = trimmed.split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return new int[]{major, minor};
        } catch (NumberFormatException e) {
            return new int[]{1, 0};
        }
    }

    private String getExtension(String fileName) {
        if (fileName == null) return "DOC";
        int idx = fileName.lastIndexOf('.');
        return idx >= 0 ? fileName.substring(idx + 1).toUpperCase(Locale.ROOT) : "DOC";
    }
}
