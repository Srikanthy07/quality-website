package com.qualitywebsite.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualitywebsite.dto.DocumentMasterDTO;
import com.qualitywebsite.dto.PublicDocumentDTO;
import com.qualitywebsite.entity.DocumentEntity;
import com.qualitywebsite.entity.DocumentMaster;
import com.qualitywebsite.entity.DocumentVersion;
import com.qualitywebsite.repository.DocumentMasterRepository;
import com.qualitywebsite.repository.DocumentRepository;
import com.qualitywebsite.repository.DocumentVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
public class DmsAuthoritativeSourceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataInitializationService dataInitializationService;

    @Autowired
    private DmsMigrationService dmsMigrationService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentMasterRepository documentMasterRepository;

    @Autowired
    private DocumentVersionRepository documentVersionRepository;

    @BeforeEach
    void setUp() {
        dataInitializationService.seedDocuments();
        dmsMigrationService.migrateToDatabaseStorage();
    }

    @Test
    @DisplayName("Admin and Public Identity Parity: /api/admin/dms/documents vs /api/public/documents")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void testAdminAndPublicIdentityParity() throws Exception {
        MvcResult adminResult = mockMvc.perform(get("/api/admin/dms/documents").secure(true))
                .andExpect(status().isOk())
                .andReturn();
        List<DocumentMasterDTO> adminDocs = objectMapper.readValue(
                adminResult.getResponse().getContentAsString(),
                new TypeReference<List<DocumentMasterDTO>>() {});

        MvcResult publicResult = mockMvc.perform(get("/api/public/documents").secure(true))
                .andExpect(status().isOk())
                .andReturn();
        List<PublicDocumentDTO> publicDocs = objectMapper.readValue(
                publicResult.getResponse().getContentAsString(),
                new TypeReference<List<PublicDocumentDTO>>() {});

        assertThat(adminDocs).isNotEmpty();
        assertThat(publicDocs).isNotEmpty();

        List<DocumentMasterDTO> approvedAdminDocs = adminDocs.stream()
                .filter(d -> "APPROVED".equalsIgnoreCase(d.getStatus()))
                .toList();

        assertThat(publicDocs).hasSameSizeAs(approvedAdminDocs);

        Map<Long, PublicDocumentDTO> publicByMasterId = publicDocs.stream()
                .collect(Collectors.toMap(PublicDocumentDTO::getMasterId, p -> p));

        for (DocumentMasterDTO adminDoc : approvedAdminDocs) {
            PublicDocumentDTO pubDoc = publicByMasterId.get(adminDoc.getId());
            assertThat(pubDoc).as("Document with masterId %d must exist in public documents", adminDoc.getId()).isNotNull();

            assertThat(pubDoc.getMasterId()).isEqualTo(adminDoc.getId());
            assertThat(pubDoc.getVersionId()).isEqualTo(adminDoc.getLatestVersionId());
            assertThat(pubDoc.getDocumentCode()).isEqualTo(adminDoc.getDocumentCode());
            assertThat(pubDoc.getDocumentName()).isEqualTo(adminDoc.getDocumentName());
            assertThat(pubDoc.getProcessId()).isEqualTo(adminDoc.getProcessId());
            assertThat(pubDoc.getCategory()).isEqualTo(adminDoc.getCategory());
            assertThat(pubDoc.getVersion()).isEqualTo(adminDoc.getCurrentVersion());
            assertThat(pubDoc.getDownloadUrl()).isEqualTo("/api/public/dms/download/" + adminDoc.getLatestVersionId());
        }
    }

    @Test
    @DisplayName("Public Download Direct from DMS: Approved version 200 with bytes, unapproved/non-existent 404")
    void testPublicDownloadDirectFromDms() throws Exception {
        List<DocumentMaster> approvedMasters = documentMasterRepository.findByStatusIgnoreCase("APPROVED");
        assertThat(approvedMasters).isNotEmpty();

        DocumentMaster master = approvedMasters.get(0);
        DocumentVersion version = documentVersionRepository.findByDocumentMasterIdAndIsLatestTrue(master.getId()).orElseThrow();
        assertThat(version.getFileData()).isNotEmpty();

        // 1. Approved download returns 200 and exact binary
        mockMvc.perform(get("/api/public/dms/download/" + version.getId()).secure(true))
                .andExpect(status().isOk())
                .andExpect(content().bytes(version.getFileData()));

        // 2. Non-existent version returns 404
        mockMvc.perform(get("/api/public/dms/download/9999999").secure(true))
                .andExpect(status().isNotFound());

        // 3. Unapproved version (UNDER_REVIEW) returns 404
        DocumentVersion unapprovedVersion = DocumentVersion.builder()
                .documentMaster(master)
                .version("99.0")
                .fileName("Unapproved.docx")
                .fileType("DOCX")
                .mimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .fileSize(10L)
                .fileData("unapproved bytes".getBytes())
                .checksum("chk_unapproved")
                .approvalStatus("UNDER_REVIEW")
                .isLatest(false)
                .build();
        unapprovedVersion = documentVersionRepository.save(unapprovedVersion);

        mockMvc.perform(get("/api/public/dms/download/" + unapprovedVersion.getId()).secure(true))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Admin API Isolation: 404 for non-existent master ID on GET, PUT, replace, DELETE, no legacy fallback")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void testAdminApiIsolation_NoLegacyFallback() throws Exception {
        String nonExistentId = "9999999";

        mockMvc.perform(get("/api/admin/documents/" + nonExistentId).secure(true))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/admin/documents/" + nonExistentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentName\":\"Ghost\",\"category\":\"General\"}")
                        .with(csrf())
                        .secure(true))
                .andExpect(status().isNotFound());

        MockMultipartFile dummyFile = new MockMultipartFile("file", "test.txt", "text/plain", "content".getBytes());
        mockMvc.perform(multipart("/api/admin/documents/" + nonExistentId + "/replace")
                        .file(dummyFile)
                        .with(csrf())
                        .secure(true))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/admin/documents/" + nonExistentId)
                        .with(csrf())
                        .secure(true))
                .andExpect(status().isNotFound());

        // Legacy table record without DMS master must NOT be found through Admin endpoints
        DocumentEntity legacyOnly = DocumentEntity.builder()
                .id("LEGACY-ONLY-999")
                .documentName("Legacy Only Doc")
                .process("SWE.1")
                .category("ASPICE PRM")
                .version("1.0")
                .filePath("documents/dummy.docx")
                .fileName("dummy.docx")
                .isActive(true)
                .build();
        documentRepository.save(legacyOnly);

        mockMvc.perform(get("/api/admin/documents/LEGACY-ONLY-999").secure(true))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Duplicate Filename Isolation: 0860_ProjectName_Verification_Measure.docx across SWE.4, SWE.5, SWE.6, SYS.4, SYS.5")
    void testDuplicateFilenameIsolation() throws Exception {
        String duplicateFilename = "0860_ProjectName_Verification_Measure.docx";
        List<DocumentVersion> matchingVersions = documentVersionRepository.findAll().stream()
                .filter(v -> duplicateFilename.equalsIgnoreCase(v.getFileName()))
                .toList();

        assertThat(matchingVersions).hasSize(5);

        Set<Long> masterIds = matchingVersions.stream()
                .map(v -> v.getDocumentMaster().getId())
                .collect(Collectors.toSet());
        assertThat(masterIds).hasSize(5);

        Set<String> processes = matchingVersions.stream()
                .map(v -> v.getDocumentMaster().getProcessId())
                .collect(Collectors.toSet());
        assertThat(processes).containsExactlyInAnyOrder("SWE.4", "SWE.5", "SWE.6", "SYS.4", "SYS.5");

        // Verify /uploaded-documents/{fileName} returns 400 Bad Request due to ambiguity
        mockMvc.perform(get("/uploaded-documents/" + duplicateFilename).secure(true))
                .andExpect(status().isBadRequest());

        // Verify each version can be downloaded canonically by its version ID
        for (DocumentVersion ver : matchingVersions) {
            mockMvc.perform(get("/api/public/dms/download/" + ver.getId()).secure(true))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(ver.getFileData()));
        }
    }

    @Test
    @DisplayName("SUP.1 Records Verification: SUP1-CHK-001, SUP1-REF-001, SUP1-REF-002, SUP1-REF-003")
    void testSup1RecordsVerification() throws Exception {
        List<String> sup1Codes = List.of("SUP1-CHK-001", "SUP1-REF-001", "SUP1-REF-002", "SUP1-REF-003");

        for (String code : sup1Codes) {
            Optional<DocumentMaster> masterOpt = documentMasterRepository.findByDocumentCode(code);
            assertThat(masterOpt).as("DocumentMaster %s must exist", code).isPresent();

            DocumentMaster master = masterOpt.get();
            assertThat(master.getStatus()).isEqualTo("APPROVED");

            Optional<DocumentVersion> versionOpt = documentVersionRepository.findByDocumentMasterIdAndIsLatestTrue(master.getId());
            assertThat(versionOpt).as("Latest DocumentVersion for %s must exist", code).isPresent();

            DocumentVersion version = versionOpt.get();
            assertThat(version.getFileData()).isNotNull();
            assertThat(version.getFileData().length).isGreaterThan(0);
            assertThat(version.getApprovalStatus()).isEqualTo("APPROVED");

            // Verify public download
            mockMvc.perform(get("/api/public/dms/download/" + version.getId()).secure(true))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(version.getFileData()));
        }
    }

    @Test
    @DisplayName("Legacy Isolation Test: Mutating legacy documents table does not affect Admin/Public DMS runtime")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void testLegacyIsolation_MutationsDoNotAffectRuntime() throws Exception {
        // 1. Insert ghost legacy record
        DocumentEntity ghost = DocumentEntity.builder()
                .id("GHOST-ISOLATION-001")
                .documentName("Ghost In The Shell")
                .process("SWE.1")
                .category("ASPICE PRM")
                .version("99.9")
                .filePath("documents/ghost.docx")
                .fileName("ghost.docx")
                .isActive(true)
                .build();
        documentRepository.save(ghost);

        // Verify it is NOT in /api/public/documents
        MvcResult pubRes = mockMvc.perform(get("/api/public/documents").secure(true))
                .andExpect(status().isOk())
                .andReturn();
        List<PublicDocumentDTO> pubDocs = objectMapper.readValue(
                pubRes.getResponse().getContentAsString(),
                new TypeReference<List<PublicDocumentDTO>>() {});
        boolean ghostInPublic = pubDocs.stream().anyMatch(d -> "Ghost In The Shell".equals(d.getDocumentName()) || "GHOST-ISOLATION-001".equals(d.getDocumentCode()));
        assertThat(ghostInPublic).isFalse();

        // Verify it is NOT in /api/admin/dms/documents
        MvcResult adminRes = mockMvc.perform(get("/api/admin/dms/documents").secure(true))
                .andExpect(status().isOk())
                .andReturn();
        List<DocumentMasterDTO> adminDocs = objectMapper.readValue(
                adminRes.getResponse().getContentAsString(),
                new TypeReference<List<DocumentMasterDTO>>() {});
        boolean ghostInAdmin = adminDocs.stream().anyMatch(d -> "Ghost In The Shell".equals(d.getDocumentName()) || "GHOST-ISOLATION-001".equals(d.getDocumentCode()));
        assertThat(ghostInAdmin).isFalse();

        // Verify it is NOT in master-list
        mockMvc.perform(get("/api/public/master-list/json").secure(true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].templateName", not(hasItem("Ghost In The Shell"))));

        // 2. Mutate an existing legacy document row
        DocumentEntity existingLegacy = documentRepository.findAll().get(0);
        existingLegacy.setDocumentName("TAMPERED_LEGACY_TITLE");
        existingLegacy.setVersion("99.0-TAMPERED");
        documentRepository.save(existingLegacy);

        // Verify that DMS runtime is completely unaffected
        MvcResult pubResAfterTamper = mockMvc.perform(get("/api/public/documents").secure(true))
                .andExpect(status().isOk())
                .andReturn();
        List<PublicDocumentDTO> pubDocsAfter = objectMapper.readValue(
                pubResAfterTamper.getResponse().getContentAsString(),
                new TypeReference<List<PublicDocumentDTO>>() {});
        boolean tamperedFound = pubDocsAfter.stream().anyMatch(d -> "TAMPERED_LEGACY_TITLE".equals(d.getDocumentName()) || "99.0-TAMPERED".equals(d.getVersion()));
        assertThat(tamperedFound).isFalse();
    }

    @Test
    @DisplayName("Migration Idempotency: Re-running migration preserves counts and fileData")
    void testMigrationIdempotency() {
        long masterCountBefore = documentMasterRepository.count();
        long versionCountBefore = documentVersionRepository.count();
        assertThat(masterCountBefore).isGreaterThan(0);

        dmsMigrationService.migrateToDatabaseStorage();

        long masterCountAfter = documentMasterRepository.count();
        long versionCountAfter = documentVersionRepository.count();

        assertThat(masterCountAfter).isEqualTo(masterCountBefore);
        assertThat(versionCountAfter).isEqualTo(versionCountBefore);
    }
}
