package com.qualitywebsite.controller;

import com.qualitywebsite.dto.DocumentMasterDTO;
import com.qualitywebsite.dto.UploadResponseDTO;
import com.qualitywebsite.entity.DeletedDocument;
import com.qualitywebsite.entity.DocumentEntity;
import com.qualitywebsite.entity.DocumentMaster;
import com.qualitywebsite.entity.DocumentVersion;
import com.qualitywebsite.repository.DeletedDocumentRepository;
import com.qualitywebsite.repository.DocumentMasterRepository;
import com.qualitywebsite.repository.DocumentRepository;
import com.qualitywebsite.repository.DocumentVersionRepository;
import com.qualitywebsite.service.DmsDocumentService;
import com.qualitywebsite.service.DmsMigrationService;
import com.qualitywebsite.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "analytics.enabled=false",
    "server.ssl.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:doc_sync_testdb;DB_CLOSE_DELAY=-1;MODE=MySQL",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
public class DocumentSynchronizationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentMasterRepository documentMasterRepository;

    @Autowired
    private DocumentVersionRepository documentVersionRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DeletedDocumentRepository deletedDocumentRepository;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DmsDocumentService dmsDocumentService;

    @Autowired
    private DmsMigrationService dmsMigrationService;

    private byte[] dummyDocx;
    private byte[] dummyXlsx;
    private byte[] dummyPdf;

    @BeforeEach
    void setUp() {
        deletedDocumentRepository.deleteAllInBatch();
        documentVersionRepository.deleteAllInBatch();
        documentMasterRepository.deleteAllInBatch();
        documentRepository.deleteAllInBatch();

        // Standard valid file headers
        dummyDocx = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x11, 0x22, 0x33, 0x44};
        dummyXlsx = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x55, 0x66, 0x77, 0x78};
        dummyPdf = new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '4'};
    }

    private DocumentMaster createMaster(String code, String proc, String cat, String docName, String status, String version) {
        DocumentMaster master = DocumentMaster.builder()
                .documentCode(code)
                .processId(proc)
                .processName(proc + " Process")
                .processGroup("Engineering Processes")
                .category(cat)
                .documentName(docName)
                .status(status)
                .currentVersion(version)
                .createdBy("admin")
                .createdDate(LocalDateTime.now())
                .updatedDate(LocalDateTime.now())
                .build();
        return documentMasterRepository.save(master);
    }

    private DocumentVersion createVersion(DocumentMaster master, String version, String fileName, String fileType,
                                          String mimeType, byte[] bytes, String approvalStatus, boolean isLatest) {
        DocumentVersion v = DocumentVersion.builder()
                .documentMaster(master)
                .version(version)
                .majorVersion(Integer.parseInt(version.split("\\.")[0]))
                .minorVersion(Integer.parseInt(version.split("\\.")[1]))
                .fileName(fileName)
                .fileType(fileType)
                .mimeType(mimeType)
                .fileSize((long) bytes.length)
                .fileData(bytes)
                .checksum(dmsDocumentService.calculateChecksum(bytes))
                .approvalStatus(approvalStatus)
                .isLatest(isLatest)
                .uploadedBy("admin")
                .uploadedDate(LocalDateTime.now())
                .approvedBy("admin")
                .approvedDate(LocalDateTime.now())
                .build();
        return documentVersionRepository.save(v);
    }

    @Test
    @DisplayName("TEST 1: Database Baseline Verification")
    @Transactional
    void test1_DatabaseBaselineVerification() {
        DocumentMaster m1 = createMaster("DOC-001", "MAN.3", "Project Management", "Project Plan", "APPROVED", "1.0");
        createVersion(m1, "1.0", "Project_Plan.docx", "DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", dummyDocx, "APPROVED", true);

        DocumentMaster m2 = createMaster("DOC-002", "SWE.1", "Software Engineering", "SRS", "APPROVED", "1.0");
        createVersion(m2, "1.0", "SRS.docx", "DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", dummyDocx, "APPROVED", true);

        List<DocumentMaster> approvedMasters = documentMasterRepository.findByStatusIgnoreCase("APPROVED");
        assertEquals(2, approvedMasters.size(), "Approved masters count must match created count");

        for (DocumentMaster m : approvedMasters) {
            Optional<DocumentVersion> latestOpt = documentVersionRepository.findByDocumentMasterIdAndIsLatestTrue(m.getId());
            assertTrue(latestOpt.isPresent(), "Every approved master must have an approved latest version");
            assertEquals("APPROVED", latestOpt.get().getApprovalStatus());
        }
    }

    @Test
    @DisplayName("TEST 2: Migration Identity Resolution")
    @Transactional
    void test2_MigrationIdentityResolution() {
        DocumentEntity leg1 = DocumentEntity.builder()
                .id("SWE4-VER-001")
                .documentName("Verification Measure")
                .process("SWE.4")
                .category("Verification")
                .fileName("0860_ProjectName_Verification_Measure.docx")
                .filePath("/documents/SWE.4/0860_ProjectName_Verification_Measure.docx")
                .version("1.0")
                .fileType("DOCX")
                .isActive(true)
                .build();
        documentRepository.save(leg1);

        DocumentEntity leg2 = DocumentEntity.builder()
                .id("SYS4-VER-001")
                .documentName("Verification Measure")
                .process("SYS.4")
                .category("Verification")
                .fileName("0860_ProjectName_Verification_Measure.docx")
                .filePath("/documents/SYS.4/0860_ProjectName_Verification_Measure.docx")
                .version("1.0")
                .fileType("DOCX")
                .isActive(true)
                .build();
        documentRepository.save(leg2);

        DocumentMaster m1 = dmsMigrationService.migrateSingleDocument(leg1, dummyDocx);
        DocumentMaster m2 = dmsMigrationService.migrateSingleDocument(leg2, dummyDocx);

        assertNotNull(m1);
        assertNotNull(m2);
        assertNotEquals(m1.getId(), m2.getId(), "Distinct processes must generate distinct DocumentMaster IDs");
        assertEquals("SWE.4", m1.getProcessId());
        assertEquals("SYS.4", m2.getProcessId());

        DocumentMaster m1Re = dmsMigrationService.migrateSingleDocument(leg1, dummyDocx);
        assertEquals(m1.getId(), m1Re.getId(), "Re-migration must resolve to existing master by code");
    }

    @Test
    @DisplayName("TEST 3: Public Document Query")
    @Transactional
    void test3_PublicDocumentQuery() throws Exception {
        DocumentMaster m1 = createMaster("GEN-001", "GENERIC", "Generic Templates", "Doc Template", "APPROVED", "1.0");
        DocumentVersion v1 = createVersion(m1, "1.0", "Doc_Template.docx", "DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", dummyDocx, "APPROVED", true);

        DocumentMaster mArchived = createMaster("ARCH-001", "GENERIC", "Generic Templates", "Old Template", "ARCHIVED", "1.0");
        createVersion(mArchived, "1.0", "Old_Template.docx", "DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", dummyDocx, "ARCHIVED", true);

        DocumentMaster mDeleted = createMaster("DEL-001", "GENERIC", "Generic Templates", "Deleted Doc", "DELETED", "1.0");
        createVersion(mDeleted, "1.0", "Deleted.docx", "DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", dummyDocx, "DELETED", true);

        mockMvc.perform(get("/api/public/documents").secure(true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].documentName", is("Doc Template")))
                .andExpect(jsonPath("$[0].filePath", is("/api/public/dms/download/" + v1.getId())))
                .andExpect(jsonPath("$[0].fileName", is("Doc_Template.docx")))
                .andExpect(jsonPath("$[0].fileType", is("DOCX")));
    }

    @Test
    @DisplayName("TEST 4: Legacy File Serving Bridge")
    @Transactional
    void test4_LegacyFileServingBridge() throws Exception {
        DocumentMaster m = createMaster("MAN3-001", "MAN.3", "Project Management", "QMP", "APPROVED", "1.0");
        DocumentVersion v = createVersion(m, "1.0", "0813_ProjectName_QMP.docx", "DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", dummyDocx, "APPROVED", true);

        DocumentEntity leg = DocumentEntity.builder()
                .id("MAN3-001")
                .documentName("QMP")
                .process("MAN.3")
                .category("Project Management")
                .fileName("0813_ProjectName_QMP.docx")
                .filePath("/documents/MAN.3/0813_ProjectName_QMP.docx")
                .version("1.0")
                .fileType("DOCX")
                .isActive(true)
                .build();
        documentRepository.save(leg);

        mockMvc.perform(get("/documents/MAN.3/0813_ProjectName_QMP.docx").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("0813_ProjectName_QMP.docx")))
                .andExpect(content().bytes(dummyDocx));
    }

    @Test
    @DisplayName("TEST 5: Duplicate Filename Disambiguation")
    @Transactional
    void test5_DuplicateFilenameDisambiguation() throws Exception {
        byte[] bytes1 = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x01};
        byte[] bytes2 = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x02};

        DocumentMaster mSwe4 = createMaster("SWE4-001", "SWE.4", "Verification", "Verification Measure", "APPROVED", "1.0");
        createVersion(mSwe4, "1.0", "0860_ProjectName_Verification_Measure.docx", "DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes1, "APPROVED", true);

        DocumentMaster mSys4 = createMaster("SYS4-001", "SYS.4", "Verification", "Verification Measure", "APPROVED", "1.0");
        createVersion(mSys4, "1.0", "0860_ProjectName_Verification_Measure.docx", "DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes2, "APPROVED", true);

        DocumentEntity legSwe4 = DocumentEntity.builder()
                .id("SWE4-001")
                .process("SWE.4")
                .category("Verification")
                .documentName("Verification Measure")
                .fileName("0860_ProjectName_Verification_Measure.docx")
                .filePath("/documents/SWE.4/0860_ProjectName_Verification_Measure.docx")
                .version("1.0")
                .fileType("DOCX")
                .isActive(true)
                .build();
        documentRepository.save(legSwe4);

        DocumentEntity legSys4 = DocumentEntity.builder()
                .id("SYS4-001")
                .process("SYS.4")
                .category("Verification")
                .documentName("Verification Measure")
                .fileName("0860_ProjectName_Verification_Measure.docx")
                .filePath("/documents/SYS.4/0860_ProjectName_Verification_Measure.docx")
                .version("1.0")
                .fileType("DOCX")
                .isActive(true)
                .build();
        documentRepository.save(legSys4);

        mockMvc.perform(get("/documents/SWE.4/0860_ProjectName_Verification_Measure.docx").secure(true))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes1));

        mockMvc.perform(get("/documents/SYS.4/0860_ProjectName_Verification_Measure.docx").secure(true))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes2));
    }

    @Test
    @DisplayName("TEST 6: Soft-Delete Synchronization")
    @Transactional
    void test6_SoftDeleteSynchronization() throws Exception {
        DocumentMaster m = createMaster("MAN3-002", "MAN.3", "Project Management", "Risk Management", "APPROVED", "1.0");
        createVersion(m, "1.0", "Risk_Plan.docx", "DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", dummyDocx, "APPROVED", true);

        DocumentEntity leg = DocumentEntity.builder()
                .id("MAN3-002")
                .process("MAN.3")
                .category("Project Management")
                .documentName("Risk Management")
                .fileName("Risk_Plan.docx")
                .filePath("/documents/MAN.3/Risk_Plan.docx")
                .version("1.0")
                .fileType("DOCX")
                .isActive(true)
                .build();
        documentRepository.save(leg);

        boolean deleted = documentService.deleteDocument("MAN3-002", "admin");
        assertTrue(deleted);

        DocumentMaster updatedMaster = documentMasterRepository.findById(m.getId()).orElseThrow();
        assertEquals("DELETED", updatedMaster.getStatus());

        DocumentVersion updatedVersion = documentVersionRepository.findByDocumentMasterIdAndIsLatestTrue(m.getId()).orElseThrow();
        assertEquals("DELETED", updatedVersion.getApprovalStatus());

        List<DocumentEntity> publicDocs = documentService.getAllDocuments();
        assertTrue(publicDocs.stream().noneMatch(d -> d.getDocumentName().equals("Risk Management")));

        List<DocumentMasterDTO> deletedList = dmsDocumentService.getAllAdminDocuments(null, null, "DELETED");
        assertTrue(deletedList.stream().anyMatch(d -> d.getDocumentName().equals("Risk Management")));
    }

    @Test
    @DisplayName("TEST 7: Permanent Deletion")
    @Transactional
    void test7_PermanentDeletion() throws Exception {
        DocumentMaster m = createMaster("SYS2-001", "SYS.2", "Requirements", "System Spec", "APPROVED", "1.0");
        DocumentVersion v = createVersion(m, "1.0", "System_Spec.docx", "DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", dummyDocx, "APPROVED", true);

        boolean ok = dmsDocumentService.deletePermanently(m.getId(), "admin");
        assertTrue(ok);

        Optional<DeletedDocument> auditOpt = deletedDocumentRepository.findByOriginalMasterId(m.getId());
        assertTrue(auditOpt.isPresent());
        assertEquals("System Spec", auditOpt.get().getDocumentName());

        mockMvc.perform(get("/api/public/dms/download/" + v.getId()).secure(true))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("TEST 8: Re-upload of Deleted Document")
    @Transactional
    void test8_ReUploadOfDeletedDocument() throws Exception {
        DocumentMaster m = createMaster("SYS2-002", "SYS.2", "Requirements", "System Architecture", "APPROVED", "1.0");
        createVersion(m, "1.0", "System_Architecture.docx", "DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", dummyDocx, "APPROVED", true);

        documentService.deleteDocument("SYS2-002", "admin");

        MockMultipartFile file = new MockMultipartFile(
                "file", "System_Architecture.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", dummyDocx);

        UploadResponseDTO resp = dmsDocumentService.uploadDocument(
                file, "Requirements", "Engineering Processes",
                "SYS.2", "System Engineering", "System Architecture",
                "1.0", "Re-uploading deleted file", "admin", false);

        assertTrue(resp.isSuccess(), "Re-upload failed with message: " + resp.getMessage() + ", action: " + resp.getAction() + ", details: " + resp.getDetails());
        assertNotNull(resp.getDocumentMasterId());
    }

    @Test
    @DisplayName("TEST 9: DMS New Version Creation")
    @Transactional
    void test9_DmsNewVersionCreation() throws Exception {
        DocumentMaster m = createMaster("SWE1-001", "SWE.1", "Requirements", "Software Spec", "APPROVED", "1.0");
        DocumentVersion v1 = createVersion(m, "1.0", "Software_Spec.docx", "DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", dummyDocx, "APPROVED", true);

        byte[] newBytes = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x77, 0x77, 0x77};
        UploadResponseDTO resp = dmsDocumentService.uploadNewVersion(
                m.getId(), newBytes, "Software_Spec_v2.docx", "DOCX",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "2.0", "Version 2.0 release", "admin");

        assertTrue(resp.isSuccess());

        dmsDocumentService.approveDocument(m.getId(), "admin");

        DocumentVersion oldV = documentVersionRepository.findById(v1.getId()).orElseThrow();
        assertFalse(oldV.getIsLatest());

        DocumentVersion newV = documentVersionRepository.findByDocumentMasterIdAndIsLatestTrue(m.getId()).orElseThrow();
        assertTrue(newV.getIsLatest());
        assertEquals("2.0", newV.getVersion());

        List<DocumentEntity> publicDocs = documentService.getAllDocuments();
        DocumentEntity doc = publicDocs.stream().filter(d -> d.getDocumentName().equals("Software Spec")).findFirst().orElseThrow();
        assertEquals("/api/public/dms/download/" + newV.getId(), doc.getFilePath());
        assertEquals("2.0", doc.getVersion());
    }

    @Test
    @DisplayName("TEST 10: Process-Level Document Count Verification")
    @Transactional
    void test10_ProcessLevelDocumentCountVerification() {
        DocumentMaster m1 = createMaster("P1-001", "SWE.1", "Requirements", "Spec 1", "APPROVED", "1.0");
        createVersion(m1, "1.0", "Spec1.docx", "DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", dummyDocx, "APPROVED", true);

        DocumentMaster m2 = createMaster("P1-002", "SWE.1", "Requirements", "Spec 2", "APPROVED", "1.0");
        createVersion(m2, "1.0", "Spec2.docx", "DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", dummyDocx, "APPROVED", true);

        DocumentMaster m3 = createMaster("P2-001", "SYS.2", "Requirements", "System Spec 1", "APPROVED", "1.0");
        createVersion(m3, "1.0", "Sys1.docx", "DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", dummyDocx, "APPROVED", true);

        List<DocumentEntity> allPublic = documentService.getAllDocuments();
        long swe1Count = allPublic.stream().filter(d -> "SWE.1".equalsIgnoreCase(d.getProcess())).count();
        long sys2Count = allPublic.stream().filter(d -> "SYS.2".equalsIgnoreCase(d.getProcess())).count();

        assertEquals(2, swe1Count);
        assertEquals(1, sys2Count);
    }

    @Test
    @DisplayName("TEST 11: Bad Filename / Legacy ID Resilience")
    @Transactional
    void test11_BadFilenameResilience() throws Exception {
        mockMvc.perform(get("/api/public/dms/download/999999").secure(true))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/documents/UNKNOWN/non_existent.docx").secure(true))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("TEST 12: Content-Disposition Filename Integrity")
    @Transactional
    void test12_ContentDispositionFilenameIntegrity() throws Exception {
        DocumentMaster m = createMaster("ASPICE-001", "SUP.1", "ASPICE PRM", "ASPICE v3.1 vs v4.0", "APPROVED", "1.0");
        DocumentVersion v = createVersion(m, "1.0", "ASPICE v3.1 vs v4.0.xlsx", "XLSX", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", dummyXlsx, "APPROVED", true);

        mockMvc.perform(get("/api/public/dms/download/" + v.getId()).secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("filename=\"ASPICE v3.1 vs v4.0.xlsx\"")))
                .andExpect(content().bytes(dummyXlsx));
    }

    @Test
    @DisplayName("TEST 13: Generic Templates & Lessons Learned Verification")
    @Transactional
    void test13_GenericTemplatesAndLessonsLearnedVerification() throws Exception {
        DocumentMaster mGen = createMaster("GEN-001", "GENERIC", "Generic Templates", "Generic Document Template", "APPROVED", "2.0");
        DocumentVersion vGen = createVersion(mGen, "2.0", "Generic_Document_Template.docx", "DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", dummyDocx, "APPROVED", true);

        DocumentMaster mLl = createMaster("LL-001", "LL", "Lessons Learned", "EV Bus Lessons Learned", "APPROVED", "1.0");
        DocumentVersion vLl = createVersion(mLl, "1.0", "EV_Bus_LL.xlsx", "XLSX", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", dummyXlsx, "APPROVED", true);

        mockMvc.perform(get("/api/public/generic-templates").secure(true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].documentName", is("Generic Document Template")))
                .andExpect(jsonPath("$[0].version", is("2.0")))
                .andExpect(jsonPath("$[0].filePath", is("/api/public/dms/download/" + vGen.getId())));

        mockMvc.perform(get("/api/public/lessons-learned").secure(true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].documentName", is("EV Bus Lessons Learned")))
                .andExpect(jsonPath("$[0].filePath", is("/api/public/dms/download/" + vLl.getId())));
    }

    @Test
    @DisplayName("TEST 14: Chrome Download Resilience")
    @Transactional
    void test14_ChromeDownloadResilience() throws Exception {
        DocumentMaster m = createMaster("CHK-001", "SUP.1", "Assessment Checklist", "Assessment Questionnaires", "APPROVED", "1.0");
        DocumentVersion v = createVersion(m, "1.0", "Assessment_Questionnaires.xlsx", "XLSX", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", dummyXlsx, "APPROVED", true);

        mockMvc.perform(get("/api/public/dms/download/" + v.getId()).secure(true))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.CONTENT_TYPE))
                .andExpect(header().exists(HttpHeaders.CONTENT_LENGTH))
                .andExpect(header().exists(HttpHeaders.CONTENT_DISPOSITION))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, String.valueOf(dummyXlsx.length)))
                .andExpect(content().bytes(dummyXlsx));
    }

    @Test
    @DisplayName("TEST 15: Checksum Duplicate Scope - Active Master Duplicate Rejected, Post-Delete Allowed")
    @Transactional
    void test15_ChecksumDuplicateScope_SameMasterRejected_DifferentMasterAllowed() throws Exception {
        DocumentMaster m1 = createMaster("SWE1-001", "SWE.1", "Requirements", "Software Spec", "APPROVED", "1.0");
        createVersion(m1, "1.0", "Software_Spec.docx", "DOCX",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", dummyDocx, "APPROVED", true);

        // 1. Uploading the EXACT same content to m1 as a new version must be rejected
        UploadResponseDTO sameMasterResp = dmsDocumentService.uploadNewVersion(
                m1.getId(), dummyDocx, "Software_Spec_New.docx", "DOCX",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "2.0", "Uploading duplicate content", "admin");

        assertFalse(sameMasterResp.isSuccess());
        assertTrue(sameMasterResp.isDuplicateChecksum());
        assertEquals("REJECTED", sameMasterResp.getAction());

        // 2. Uploading the EXACT same content while m1 is ACTIVE must be rejected
        MockMultipartFile fileForNewMaster = new MockMultipartFile(
                "file", "System_Spec.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", dummyDocx);

        UploadResponseDTO activeDupResp = dmsDocumentService.uploadDocument(
                fileForNewMaster, "Requirements", "Engineering Processes",
                "SYS.2", "System Engineering", "System Spec",
                "1.0", "Uploading duplicate file content", "admin", false);

        assertFalse(activeDupResp.isSuccess(), "Active duplicate content upload must be rejected");
        assertTrue(activeDupResp.isDuplicateChecksum());

        // 3. Once m1 is deleted, re-uploading the same file content to SYS.2 must be ALLOWED
        documentService.deleteDocument("SWE1-001", "admin");

        UploadResponseDTO reuploadResp = dmsDocumentService.uploadDocument(
                fileForNewMaster, "Requirements", "Engineering Processes",
                "SYS.2", "System Engineering", "System Spec",
                "1.0", "Uploading after previous document deleted", "admin", false);

        assertTrue(reuploadResp.isSuccess(), "Upload after previous master deleted must be allowed");
        assertFalse(reuploadResp.isDuplicateChecksum());
        assertNotNull(reuploadResp.getDocumentMasterId());
    }

    @Test
    @DisplayName("TEST 16: Ambiguous Static Document - Returns 400 Bad Request instead of random candidate")
    @Transactional
    void test16_AmbiguousStaticDocument_ReturnsBadRequest() throws Exception {
        byte[] b1 = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x10};
        byte[] b2 = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x20};

        DocumentMaster m1 = createMaster("SWE1-COMM", "SWE.1", "Verification", "Common Review", "APPROVED", "1.0");
        createVersion(m1, "1.0", "common_review.docx", "DOCX",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", b1, "APPROVED", true);

        DocumentMaster m2 = createMaster("SYS2-COMM", "SYS.2", "Verification", "Common Review", "APPROVED", "1.0");
        createVersion(m2, "1.0", "common_review.docx", "DOCX",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", b2, "APPROVED", true);

        // Path does NOT contain SWE.1 or SYS.2 -> Truly ambiguous
        mockMvc.perform(get("/documents/UNKNOWN_FOLDER/common_review.docx").secure(true))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("TEST 17: Public Endpoints Return PublicDocumentDTO with DMS fields")
    @Transactional
    void test17_PublicEndpointsReturnPublicDocumentDTO() throws Exception {
        DocumentMaster mGen = createMaster("GEN-002", "GENERIC", "Generic Templates", "Company Standard Template", "APPROVED", "1.0");
        DocumentVersion vGen = createVersion(mGen, "1.0", "Company_Standard_Template.docx", "DOCX",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", dummyDocx, "APPROVED", true);

        mockMvc.perform(get("/data/generic-templates.json").secure(true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].masterId", is(mGen.getId().intValue())))
                .andExpect(jsonPath("$[0].documentCode", is("GEN-002")))
                .andExpect(jsonPath("$[0].fileName", is("Company_Standard_Template.docx")))
                .andExpect(jsonPath("$[0].fileType", is("DOCX")))
                .andExpect(jsonPath("$[0].status", is("APPROVED")))
                .andExpect(jsonPath("$[0].downloadUrl", is("/api/public/dms/download/" + vGen.getId())));
    }
}
