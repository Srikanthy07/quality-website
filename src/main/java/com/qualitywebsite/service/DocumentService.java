package com.qualitywebsite.service;

import com.qualitywebsite.entity.DocumentEntity;
import com.qualitywebsite.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;

import com.qualitywebsite.dto.PublicDocumentDTO;
import com.qualitywebsite.entity.DeletedDocument;
import com.qualitywebsite.entity.DocumentMaster;
import com.qualitywebsite.entity.DocumentVersion;
import com.qualitywebsite.repository.DeletedDocumentRepository;
import com.qualitywebsite.repository.DocumentMasterRepository;
import com.qualitywebsite.repository.DocumentVersionRepository;

import java.util.*;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final DocumentMasterRepository documentMasterRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final DeletedDocumentRepository deletedDocumentRepository;
    private final ActivityLogService activityLogService;

    @Value("${app.upload.dir:./uploaded-documents}")
    private String uploadDir;

    public List<PublicDocumentDTO> getAllPublicDocuments() {
        List<PublicDocumentDTO> list = new ArrayList<>();
        List<DocumentMaster> approvedMasters = documentMasterRepository.findByStatusIgnoreCase("APPROVED");
        for (DocumentMaster master : approvedMasters) {
            Optional<DocumentVersion> versionOpt = findLatestApprovedVersion(master.getId());
            if (versionOpt.isPresent()) {
                list.add(toPublicDocumentDTO(master, versionOpt.get()));
            }
        }
        return list;
    }

    public List<PublicDocumentDTO> getPublicDocumentsByCategory(String category) {
        List<PublicDocumentDTO> list = new ArrayList<>();
        if (category == null || category.trim().isEmpty()) {
            return list;
        }
        List<DocumentMaster> approvedMasters = documentMasterRepository.findByStatusIgnoreCase("APPROVED");
        for (DocumentMaster master : approvedMasters) {
            if (master.getCategory() != null && master.getCategory().equalsIgnoreCase(category.trim())) {
                Optional<DocumentVersion> versionOpt = findLatestApprovedVersion(master.getId());
                if (versionOpt.isPresent()) {
                    list.add(toPublicDocumentDTO(master, versionOpt.get()));
                }
            }
        }
        return list;
    }

    public List<PublicDocumentDTO> searchPublicDocuments(String query, String category) {
        List<PublicDocumentDTO> list = new ArrayList<>();
        List<DocumentMaster> approvedMasters = documentMasterRepository.findByStatusIgnoreCase("APPROVED");
        String q = (query != null) ? query.trim().toLowerCase(Locale.ROOT) : "";
        for (DocumentMaster master : approvedMasters) {
            boolean matchesCategory = (category == null || category.trim().isEmpty() ||
                    (master.getCategory() != null && master.getCategory().equalsIgnoreCase(category.trim())));
            if (!matchesCategory) {
                continue;
            }
            boolean matchesQuery = q.isEmpty()
                    || (master.getDocumentName() != null && master.getDocumentName().toLowerCase(Locale.ROOT).contains(q))
                    || (master.getProcessId() != null && master.getProcessId().toLowerCase(Locale.ROOT).contains(q))
                    || (master.getProcessGroup() != null && master.getProcessGroup().toLowerCase(Locale.ROOT).contains(q))
                    || (master.getDescription() != null && master.getDescription().toLowerCase(Locale.ROOT).contains(q))
                    || (master.getDocumentCode() != null && master.getDocumentCode().toLowerCase(Locale.ROOT).contains(q));

            if (matchesQuery) {
                Optional<DocumentVersion> versionOpt = findLatestApprovedVersion(master.getId());
                if (versionOpt.isPresent()) {
                    list.add(toPublicDocumentDTO(master, versionOpt.get()));
                }
            }
        }
        return list;
    }

    public Optional<PublicDocumentDTO> getPublicDocumentById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        Optional<DocumentMaster> masterOpt = Optional.empty();
        if (id.startsWith("DMS-")) {
            try {
                masterOpt = documentMasterRepository.findById(Long.parseLong(id.substring(4)));
            } catch (NumberFormatException ignored) {}
        }
        if (masterOpt.isEmpty()) {
            masterOpt = documentMasterRepository.findByDocumentCode(id);
        }
        if (masterOpt.isEmpty()) {
            try {
                masterOpt = documentMasterRepository.findById(Long.parseLong(id));
            } catch (NumberFormatException ignored) {}
        }
        if (masterOpt.isPresent()) {
            DocumentMaster master = masterOpt.get();
            if ("APPROVED".equalsIgnoreCase(master.getStatus())) {
                Optional<DocumentVersion> versionOpt = findLatestApprovedVersion(master.getId());
                if (versionOpt.isPresent()) {
                    return Optional.of(toPublicDocumentDTO(master, versionOpt.get()));
                }
            }
        }
        return Optional.empty();
    }

    public List<DocumentEntity> getAllDocuments() {
        List<DocumentEntity> list = new ArrayList<>();
        List<DocumentMaster> approvedMasters = documentMasterRepository.findByStatusIgnoreCase("APPROVED");
        for (DocumentMaster master : approvedMasters) {
            Optional<DocumentVersion> versionOpt = findLatestApprovedVersion(master.getId());
            if (versionOpt.isPresent()) {
                list.add(toDocumentEntity(master, versionOpt.get()));
            }
        }
        return list;
    }

    // Search and filter documents by query and category
    public List<DocumentEntity> searchAndFilter(String query, String category) {
        List<DocumentEntity> list = new ArrayList<>();
        List<DocumentMaster> approvedMasters = documentMasterRepository.findByStatusIgnoreCase("APPROVED");
        String q = (query != null) ? query.trim().toLowerCase(Locale.ROOT) : "";
        for (DocumentMaster master : approvedMasters) {
            boolean matchesCategory = (category == null || category.trim().isEmpty() ||
                    (master.getCategory() != null && master.getCategory().equalsIgnoreCase(category.trim())));
            if (!matchesCategory) {
                continue;
            }
            boolean matchesQuery = q.isEmpty()
                    || (master.getDocumentName() != null && master.getDocumentName().toLowerCase(Locale.ROOT).contains(q))
                    || (master.getProcessId() != null && master.getProcessId().toLowerCase(Locale.ROOT).contains(q))
                    || (master.getProcessGroup() != null && master.getProcessGroup().toLowerCase(Locale.ROOT).contains(q))
                    || (master.getDescription() != null && master.getDescription().toLowerCase(Locale.ROOT).contains(q))
                    || (master.getDocumentCode() != null && master.getDocumentCode().toLowerCase(Locale.ROOT).contains(q));

            if (matchesQuery) {
                Optional<DocumentVersion> versionOpt = findLatestApprovedVersion(master.getId());
                if (versionOpt.isPresent()) {
                    list.add(toDocumentEntity(master, versionOpt.get()));
                }
            }
        }
        return list;
    }

    public Optional<DocumentEntity> getById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        Optional<DocumentMaster> masterOpt = Optional.empty();
        if (id.startsWith("DMS-")) {
            try {
                masterOpt = documentMasterRepository.findById(Long.parseLong(id.substring(4)));
            } catch (NumberFormatException ignored) {}
        }
        if (masterOpt.isEmpty()) {
            masterOpt = documentMasterRepository.findByDocumentCode(id);
        }
        if (masterOpt.isEmpty()) {
            try {
                masterOpt = documentMasterRepository.findById(Long.parseLong(id));
            } catch (NumberFormatException ignored) {}
        }
        if (masterOpt.isPresent()) {
            DocumentMaster master = masterOpt.get();
            if ("APPROVED".equalsIgnoreCase(master.getStatus())) {
                Optional<DocumentVersion> versionOpt = findLatestApprovedVersion(master.getId());
                if (versionOpt.isPresent()) {
                    return Optional.of(toDocumentEntity(master, versionOpt.get()));
                }
            }
        }
        return Optional.empty();
    }

    public List<DocumentEntity> getByCategory(String category) {
        List<DocumentEntity> list = new ArrayList<>();
        if (category == null || category.trim().isEmpty()) {
            return list;
        }
        List<DocumentMaster> approvedMasters = documentMasterRepository.findByStatusIgnoreCase("APPROVED");
        for (DocumentMaster master : approvedMasters) {
            if (master.getCategory() != null && master.getCategory().equalsIgnoreCase(category.trim())) {
                Optional<DocumentVersion> versionOpt = findLatestApprovedVersion(master.getId());
                if (versionOpt.isPresent()) {
                    list.add(toDocumentEntity(master, versionOpt.get()));
                }
            }
        }
        return list;
    }

    public Optional<DocumentVersion> findLatestApprovedVersion(Long masterId) {
        if (masterId == null) return Optional.empty();
        Optional<DocumentVersion> isLatestOpt = documentVersionRepository.findByDocumentMasterIdAndIsLatestTrue(masterId);
        if (isLatestOpt.isPresent() && "APPROVED".equalsIgnoreCase(isLatestOpt.get().getApprovalStatus())) {
            return isLatestOpt;
        }
        List<DocumentVersion> versions = documentVersionRepository.findByDocumentMasterIdOrderByUploadedDateDesc(masterId);
        return versions.stream()
                .filter(v -> "APPROVED".equalsIgnoreCase(v.getApprovalStatus()))
                .findFirst();
    }

    public DocumentEntity syncVersionFromMaster(DocumentEntity doc) {
        if (doc == null) return null;
        Optional<DocumentMaster> masterOpt = findMatchingMaster(doc);
        if (masterOpt.isPresent()) {
            DocumentMaster master = masterOpt.get();
            if (master.getCurrentVersion() != null && !master.getCurrentVersion().isBlank()) {
                doc.setVersion(master.getCurrentVersion());
            }
            Optional<DocumentVersion> versionOpt = findLatestApprovedVersion(master.getId());
            if (versionOpt.isPresent()) {
                DocumentVersion version = versionOpt.get();
                if ("APPROVED".equalsIgnoreCase(master.getStatus())) {
                    doc.setVersion(version.getVersion());
                    doc.setFilePath("/api/public/dms/download/" + version.getId());
                    doc.setFileType(version.getFileType());
                    doc.setFileName(version.getFileName());
                }
            }
        }
        return doc;
    }

    public Optional<DocumentMaster> findMatchingMaster(DocumentEntity doc) {
        if (doc == null) return Optional.empty();
        if (doc.getId() != null && doc.getId().startsWith("DMS-")) {
            try {
                Long masterId = Long.parseLong(doc.getId().substring(4));
                Optional<DocumentMaster> byId = documentMasterRepository.findById(masterId);
                if (byId.isPresent()) return byId;
            } catch (NumberFormatException ignored) {}
        }
        if (doc.getId() != null && !doc.getId().isBlank()) {
            Optional<DocumentMaster> byCode = documentMasterRepository.findByDocumentCode(doc.getId());
            if (byCode.isPresent()) return byCode;
        }
        return documentMasterRepository
                .findByProcessIdIgnoreCaseAndCategoryIgnoreCaseAndDocumentNameIgnoreCase(
                        doc.getProcess(), doc.getCategory(), doc.getDocumentName());
    }

    public boolean isMasterApproved(DocumentEntity doc) {
        if (doc == null) return false;
        Optional<DocumentMaster> masterOpt = findMatchingMaster(doc);
        if (masterOpt.isPresent()) {
            DocumentMaster master = masterOpt.get();
            if (!"APPROVED".equalsIgnoreCase(master.getStatus())) return false;
            return findLatestApprovedVersion(master.getId()).isPresent();
        }
        return false;
    }

    public PublicDocumentDTO toPublicDocumentDTO(DocumentMaster master, DocumentVersion version) {
        String entityId = (master.getDocumentCode() != null && !master.getDocumentCode().isBlank())
                ? master.getDocumentCode()
                : "DMS-" + master.getId();

        String downloadUrl = version != null ? "/api/public/dms/download/" + version.getId() : null;

        return PublicDocumentDTO.builder()
                .masterId(master.getId())
                .documentCode(entityId)
                .documentName(master.getDocumentName())
                .processId(master.getProcessId())
                .processName(master.getProcessName() != null ? master.getProcessName() : master.getProcessId())
                .processGroup(master.getProcessGroup() != null ? master.getProcessGroup() : "General")
                .category(master.getCategory())
                .description(master.getDescription())
                .version(version != null && version.getVersion() != null ? version.getVersion() : master.getCurrentVersion())
                .versionId(version != null ? version.getId() : null)
                .fileName(version != null ? version.getFileName() : null)
                .fileType(version != null ? version.getFileType() : null)
                .mimeType(version != null ? version.getMimeType() : null)
                .fileSize(version != null ? version.getFileSize() : null)
                .status(master.getStatus())
                .downloadUrl(downloadUrl)
                .createdDate(version != null && version.getUploadedDate() != null ? version.getUploadedDate() : master.getCreatedDate())
                .updatedDate(master.getUpdatedDate() != null ? master.getUpdatedDate() : LocalDateTime.now())
                .build();
    }

    private DocumentEntity toDocumentEntity(DocumentMaster master, DocumentVersion version) {
        String entityId = (master.getDocumentCode() != null && !master.getDocumentCode().isBlank())
                ? master.getDocumentCode()
                : "DMS-" + master.getId();

        return DocumentEntity.builder()
                .id(entityId)
                .documentName(master.getDocumentName())
                .process(master.getProcessId())
                .processGroup(master.getProcessGroup() != null ? master.getProcessGroup() : "General")
                .category(master.getCategory())
                .version(version != null && version.getVersion() != null ? version.getVersion() : master.getCurrentVersion())
                .fileType(version != null ? version.getFileType() : null)
                .filePath(version != null ? "/api/public/dms/download/" + version.getId() : null)
                .fileName(version != null ? version.getFileName() : null)
                .fileSize(version != null ? version.getFileSize() : null)
                .description(master.getDescription())
                .isActive(true)
                .createdAt(version != null && version.getUploadedDate() != null ? version.getUploadedDate() : master.getCreatedDate())
                .updatedAt(master.getUpdatedDate() != null ? master.getUpdatedDate() : LocalDateTime.now())
                .build();
    }

    public boolean isDuplicate(String id, String documentName, String category, String process) {
        // Check by custom ID first (exact primary-key lookup — O(1))
        if (id != null && documentRepository.existsById(id)) {
            return true;
        }
        // RC-6 fix: replaced full documentRepository.findAll() + Java-level loop (O(N) table scan)
        // with a single SELECT EXISTS(...) query executed entirely in the database.
        return documentRepository
                .existsByDocumentNameIgnoreCaseAndCategoryIgnoreCaseAndProcessIgnoreCase(
                        documentName, category, process);
    }

    public DocumentEntity saveDocument(DocumentEntity doc, MultipartFile file, String username) throws IOException {
        String fileExt = "";
        if (file != null && !file.isEmpty()) {
            String originalName = StringUtils.cleanPath(Objects.requireNonNull(file.getOriginalFilename()));
            fileExt = originalName.contains(".") ? originalName.substring(originalName.lastIndexOf(".") + 1).toUpperCase() : "DOC";

            String storedFileName = UUID.randomUUID().toString() + "_" + originalName;
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }
            Path targetPath = uploadPath.resolve(storedFileName);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            doc.setFileName(originalName);
            doc.setFilePath("/uploaded-documents/" + storedFileName);
            doc.setFileType(fileExt);
            doc.setFileSize(file.getSize());
        }

        if (doc.getId() == null || doc.getId().trim().isEmpty()) {
            String generatedId = generateDocumentId(doc.getProcess(), doc.getCategory());
            doc.setId(generatedId);
        }

        if (doc.getFileType() == null || doc.getFileType().isEmpty()) {
            doc.setFileType("DOC");
        }

        DocumentEntity saved = documentRepository.save(doc);
        activityLogService.logActivity(username, "Uploaded Document", "Uploaded " + saved.getProcess() + " " + saved.getDocumentName() + " (v" + saved.getVersion() + ")");
        return saved;
    }

    public DocumentEntity updateMetadata(String id, DocumentEntity updateData, String username) {
        DocumentEntity existing = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));

        existing.setDocumentName(updateData.getDocumentName());
        existing.setVersion(updateData.getVersion());
        existing.setDescription(updateData.getDescription());
        existing.setProcess(updateData.getProcess());
        existing.setProcessGroup(updateData.getProcessGroup());
        existing.setCategory(updateData.getCategory());
        existing.setUpdatedAt(LocalDateTime.now());

        DocumentEntity saved = documentRepository.save(existing);
        activityLogService.logActivity(username, "Updated Document", "Updated details for " + saved.getProcess() + " " + saved.getDocumentName());
        return saved;
    }

    public DocumentEntity replaceFile(String id, MultipartFile file, String username) throws IOException {
        DocumentEntity existing = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be empty");
        }

        // Delete old file if present inside uploadDir
        deletePhysicalFile(existing.getFilePath());

        String originalName = StringUtils.cleanPath(Objects.requireNonNull(file.getOriginalFilename()));
        String fileExt = originalName.contains(".") ? originalName.substring(originalName.lastIndexOf(".") + 1).toUpperCase() : "DOC";
        String storedFileName = UUID.randomUUID().toString() + "_" + originalName;

        Path uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }
        Path targetPath = uploadPath.resolve(storedFileName);
        Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

        existing.setFileName(originalName);
        existing.setFilePath("/uploaded-documents/" + storedFileName);
        existing.setFileType(fileExt);
        existing.setFileSize(file.getSize());
        existing.setUpdatedAt(LocalDateTime.now());

        DocumentEntity saved = documentRepository.save(existing);
        activityLogService.logActivity(username, "Replaced File", "Replaced file for " + saved.getProcess() + " " + saved.getDocumentName());
        return saved;
    }

    @Transactional
    public boolean deleteDocument(String id, String username) {
        Optional<DocumentEntity> opt = documentRepository.findById(id);
        DocumentEntity doc = opt.orElse(null);
        if (doc != null) {
            doc.setIsActive(false);
            documentRepository.save(doc);
        }

        // Synchronize with DMS DocumentMaster and DocumentVersion if present
        Optional<DocumentMaster> masterOpt = Optional.empty();
        if (id != null && id.startsWith("DMS-")) {
            try {
                masterOpt = documentMasterRepository.findById(Long.parseLong(id.substring(4)));
            } catch (NumberFormatException ignored) {}
        }
        if (masterOpt.isEmpty() && id != null && !id.isBlank()) {
            masterOpt = documentMasterRepository.findByDocumentCode(id);
        }
        if (masterOpt.isEmpty() && doc != null) {
            masterOpt = documentMasterRepository.findByProcessIdIgnoreCaseAndCategoryIgnoreCaseAndDocumentNameIgnoreCase(
                    doc.getProcess(), doc.getCategory(), doc.getDocumentName());
        }
        if (masterOpt.isPresent()) {
            DocumentMaster master = masterOpt.get();
            master.setStatus("DELETED");
            master.setDeletedBy(username != null ? username : "admin");
            master.setDeletedDate(LocalDateTime.now());
            master.setUpdatedDate(LocalDateTime.now());
            documentMasterRepository.save(master);

            Optional<DocumentVersion> latestOpt = documentVersionRepository.findByDocumentMasterIdAndIsLatestTrue(master.getId());
            if (latestOpt.isPresent()) {
                DocumentVersion latest = latestOpt.get();
                latest.setApprovalStatus("DELETED");
                documentVersionRepository.save(latest);
            }

            DocumentVersion latest = latestOpt.orElse(null);
            DeletedDocument delDoc = deletedDocumentRepository.findByOriginalMasterId(master.getId())
                    .orElseGet(() -> DeletedDocument.builder().originalMasterId(master.getId()).build());
            delDoc.setDocumentCode(master.getDocumentCode());
            delDoc.setProcessId(master.getProcessId());
            delDoc.setProcessName(master.getProcessName());
            delDoc.setProcessGroup(master.getProcessGroup());
            delDoc.setCategory(master.getCategory() != null && !master.getCategory().isBlank() ? master.getCategory() : (doc != null && doc.getCategory() != null ? doc.getCategory() : "General"));
            delDoc.setDocumentName(master.getDocumentName() != null && !master.getDocumentName().isBlank() ? master.getDocumentName() : (doc != null && doc.getDocumentName() != null ? doc.getDocumentName() : "Untitled Document"));
            delDoc.setDescription(master.getDescription() != null ? master.getDescription() : (doc != null ? doc.getDescription() : null));
            delDoc.setCurrentVersion(master.getCurrentVersion() != null ? master.getCurrentVersion() : (doc != null ? doc.getVersion() : null));
            delDoc.setFileName(latest != null ? latest.getFileName() : (doc != null ? doc.getFileName() : null));
            delDoc.setFileType(latest != null ? latest.getFileType() : (doc != null ? doc.getFileType() : null));
            delDoc.setMimeType(latest != null ? latest.getMimeType() : null);
            delDoc.setFileSize(latest != null ? latest.getFileSize() : (doc != null ? doc.getFileSize() : null));
            delDoc.setFileData(latest != null ? latest.getFileData() : null);
            delDoc.setChecksum(latest != null ? latest.getChecksum() : null);
            delDoc.setCreatedBy(master.getCreatedBy());
            delDoc.setCreatedDate(master.getCreatedDate());
            delDoc.setDeletedBy(username != null ? username : "admin");
            delDoc.setDeletedDate(master.getDeletedDate() != null ? master.getDeletedDate() : LocalDateTime.now());
            deletedDocumentRepository.save(delDoc);

            if (doc != null) {
                deletePhysicalFile(doc.getFilePath());
            }
            activityLogService.logActivity(username, "Deleted Document", "Soft-deleted " + master.getDocumentName() + " (Master ID: " + master.getId() + ")");
            return true;
        }

        if (doc != null) {
            deletePhysicalFile(doc.getFilePath());
            activityLogService.logActivity(username, "Deleted Document", "Soft-deleted " + doc.getProcess() + " " + doc.getDocumentName());
            return true;
        }

        return false;
    }

    private void deletePhysicalFile(String filePath) {
        if (filePath != null && filePath.startsWith("/uploaded-documents/")) {
            String fileName = filePath.substring("/uploaded-documents/".length());
            try {
                Path fileToDel = Paths.get(uploadDir).resolve(fileName);
                Files.deleteIfExists(fileToDel);
            } catch (Exception ignored) {
            }
        }
    }

    private String generateDocumentId(String process, String category) {
        String prefix = (process != null && !process.trim().isEmpty()) ? process.replace(".", "").toUpperCase() : "DOC";
        long count = documentRepository.count() + 1;
        String id = prefix + "-" + String.format("%03d", count);
        while (documentRepository.existsById(id)) {
            count++;
            id = prefix + "-" + String.format("%03d", count);
        }
        return id;
    }

    public Map<String, Object> getDashboardStats() {
        Map<String, Object> stats = new HashMap<>();
        long totalApproved = documentMasterRepository.countByStatus("APPROVED");
        long aspicePrm = documentMasterRepository.countByCategoryIgnoreCaseAndStatus("ASPICE PRM", "APPROVED");
        long generic   = documentMasterRepository.countByCategoryIgnoreCaseAndStatus("Generic Templates", "APPROVED");
        long lessons   = documentMasterRepository.countByCategoryIgnoreCaseAndStatus("Lessons Learned", "APPROVED");
        long checklist = documentMasterRepository.countByCategoryIgnoreCaseAndStatus("Assessment Checklist", "APPROVED");
        LocalDateTime latest = documentMasterRepository.findLatestUploadDate();

        stats.put("totalDocuments", totalApproved);
        stats.put("aspicePrmDocuments", aspicePrm);
        stats.put("genericTemplates", generic);
        stats.put("lessonsLearned", lessons);
        stats.put("assessmentChecklists", checklist);
        stats.put("latestUploadDate", latest != null ? latest.toString() : "N/A");
        return stats;
    }
}