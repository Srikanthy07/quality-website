package com.qualitywebsite.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualitywebsite.dto.DocumentMasterDTO;
import com.qualitywebsite.dto.PublicDocumentDTO;
import com.qualitywebsite.dto.UploadResponseDTO;
import com.qualitywebsite.entity.DocumentMaster;
import com.qualitywebsite.entity.DocumentVersion;
import com.qualitywebsite.repository.DocumentMasterRepository;
import com.qualitywebsite.repository.DocumentVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "server.ssl.enabled=false",
    "server.servlet.session.cookie.secure=false",
    "spring.datasource.url=jdbc:h2:mem:desc_persistence_testdb;DB_CLOSE_DELAY=-1;MODE=MySQL",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class DocumentUploadDescriptionPersistenceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DmsDocumentService dmsDocumentService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentMasterRepository documentMasterRepository;

    @Autowired
    private DocumentVersionRepository documentVersionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        documentVersionRepository.deleteAll();
        documentMasterRepository.deleteAll();
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("1. Upload document with description -> persisted to document_master.description and exposed in public API")
    void testUploadWithDescription_PersistedToDatabaseAndPublicApi() throws Exception {
        String docName = "Kugler Maag by UL Solutions - Automotive SPICE 4.0";
        String descriptionText = "Overview of the key changes from Automotive SPICE® PAM 3.1 to 4.0, including updates to process groups, terminology, capability levels, and assessment guidelines.";
        byte[] pdfBytes = "%PDF-1.4 Kugler Maag Automotive SPICE 4.0 Content".getBytes();

        MockMultipartFile file = new MockMultipartFile(
                "file", "Kugler_Maag_Automotive_SPICE_4.0.pdf", "application/pdf", pdfBytes
        );

        // Upload via /api/admin/dms/upload endpoint
        mockMvc.perform(multipart("/api/admin/dms/upload")
                        .file(file)
                        .secure(true)
                        .param("category", "ASPICE PRM")
                        .param("processId", "SUP.1")
                        .param("processGroup", "Supporting Process Group")
                        .param("documentName", docName)
                        .param("version", "1.0")
                        .param("description", descriptionText)
                        .param("remarks", descriptionText)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.existingDocument.description").value(descriptionText));

        // 1. Direct Database verification: SELECT id, document_name, description, status FROM document_master
        List<DocumentMaster> masters = documentMasterRepository.findAll();
        assertThat(masters).hasSize(1);
        DocumentMaster savedMaster = masters.get(0);

        assertThat(savedMaster.getDocumentName()).isEqualTo(docName);
        assertThat(savedMaster.getDescription()).isNotNull();
        assertThat(savedMaster.getDescription()).isEqualTo(descriptionText);
        assertThat(savedMaster.getStatus()).isEqualTo("UNDER_REVIEW");

        // 2. Approve document so it becomes visible on public endpoints
        dmsDocumentService.approveDocument(savedMaster.getId(), "admin");
        DocumentMaster approvedMaster = documentMasterRepository.findById(savedMaster.getId()).orElseThrow();
        assertThat(approvedMaster.getStatus()).isEqualTo("APPROVED");
        assertThat(approvedMaster.getDescription()).isEqualTo(descriptionText);

        // 3. Admin API verification: GET /api/admin/dms/documents/{id}
        mockMvc.perform(get("/api/admin/dms/documents/" + savedMaster.getId()).secure(true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(descriptionText))
                .andExpect(jsonPath("$.documentName").value(docName))
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 4. Public API verification: GET /api/public/documents
        mockMvc.perform(get("/api/public/documents").secure(true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].documentName").value(docName))
                .andExpect(jsonPath("$[0].description").value(descriptionText))
                .andExpect(jsonPath("$[0].status").value("APPROVED"));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("2. Upload document without description -> document_master.description is NULL")
    void testUploadWithoutDescription_DatabaseDescriptionIsNull() throws Exception {
        byte[] pdfBytes = "%PDF-1.4 Empty Description Document".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "No_Desc_Doc.pdf", "application/pdf", pdfBytes
        );

        mockMvc.perform(multipart("/api/admin/dms/upload")
                        .file(file)
                        .secure(true)
                        .param("category", "ASPICE PRM")
                        .param("processId", "SWE.1")
                        .param("processGroup", "Software Engineering")
                        .param("documentName", "SWE.1 Software Requirements Checklist")
                        .param("version", "1.0")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.existingDocument.description").value(nullValue()));

        List<DocumentMaster> masters = documentMasterRepository.findAll();
        assertThat(masters).hasSize(1);
        DocumentMaster savedMaster = masters.get(0);
        assertThat(savedMaster.getDescription()).isNull();

        // Admin API returns null description
        mockMvc.perform(get("/api/admin/dms/documents/" + savedMaster.getId()).secure(true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(nullValue()));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("3. Edit metadata - Case 1: Set description -> Persisted")
    void testEditMetadata_SetDescription_Persisted() throws Exception {
        // Create initial doc with null description
        MockMultipartFile file = new MockMultipartFile(
                "file", "Edit_Test.pdf", "application/pdf", "%PDF-1.4 Edit Test".getBytes()
        );
        UploadResponseDTO uploadResp = dmsDocumentService.uploadDocument(
                file, "ASPICE PRM", "System Engineering", "SYS.2", "System Requirements",
                "SYS.2 System Requirements Guide", "1.0", null, "admin", false
        );
        Long masterId = uploadResp.getDocumentMasterId();

        DocumentMaster initial = documentMasterRepository.findById(masterId).orElseThrow();
        assertThat(initial.getDescription()).isNull();

        // Update description with text
        String newDesc = "New detailed guide for SYS.2 system requirements.";
        DocumentMasterDTO updateDto = DocumentMasterDTO.builder()
                .entityVersion(initial.getEntityVersion())
                .documentName(initial.getDocumentName())
                .category(initial.getCategory())
                .processId(initial.getProcessId())
                .processGroup(initial.getProcessGroup())
                .description(newDesc)
                .build();

        mockMvc.perform(put("/api/admin/dms/documents/" + masterId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto))
                        .secure(true)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(newDesc));

        DocumentMaster updated = documentMasterRepository.findById(masterId).orElseThrow();
        assertThat(updated.getDescription()).isEqualTo(newDesc);
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("4. Edit metadata - Case 2: Clear description (empty string) -> Database becomes NULL")
    void testEditMetadata_ClearDescription_DatabaseBecomesNull() throws Exception {
        // Create doc WITH description
        MockMultipartFile file = new MockMultipartFile(
                "file", "Clear_Test.pdf", "application/pdf", "%PDF-1.4 Clear Test".getBytes()
        );
        UploadResponseDTO uploadResp = dmsDocumentService.uploadDocument(
                file, "ASPICE PRM", "System Engineering", "SYS.3", "System Architecture",
                "SYS.3 Architecture Specification", "1.0", "Existing description to be cleared",
                "Initial upload", "admin", false
        );
        Long masterId = uploadResp.getDocumentMasterId();

        DocumentMaster initial = documentMasterRepository.findById(masterId).orElseThrow();
        assertThat(initial.getDescription()).isEqualTo("Existing description to be cleared");

        // Send empty string "" to clear description
        DocumentMasterDTO clearDto = DocumentMasterDTO.builder()
                .entityVersion(initial.getEntityVersion())
                .documentName(initial.getDocumentName())
                .category(initial.getCategory())
                .processId(initial.getProcessId())
                .processGroup(initial.getProcessGroup())
                .description("") // Explicit clear
                .build();

        mockMvc.perform(put("/api/admin/dms/documents/" + masterId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(clearDto))
                        .secure(true)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(nullValue()));

        DocumentMaster cleared = documentMasterRepository.findById(masterId).orElseThrow();
        assertThat(cleared.getDescription()).isNull();
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("5. Edit metadata - Case 3: Omit description (null in DTO) -> Existing description remains unchanged")
    void testEditMetadata_OmitDescription_PreservesExistingDescription() throws Exception {
        // Create doc WITH description
        MockMultipartFile file = new MockMultipartFile(
                "file", "Omit_Test.pdf", "application/pdf", "%PDF-1.4 Omit Test".getBytes()
        );
        String originalDesc = "Crucial description that must not be erased during rename";
        UploadResponseDTO uploadResp = dmsDocumentService.uploadDocument(
                file, "ASPICE PRM", "Software Engineering", "SWE.2", "Software Architecture",
                "SWE.2 Software Architectural Design", "1.0", originalDesc,
                "Initial remarks", "admin", false
        );
        Long masterId = uploadResp.getDocumentMasterId();

        DocumentMaster initial = documentMasterRepository.findById(masterId).orElseThrow();
        assertThat(initial.getDescription()).isEqualTo(originalDesc);

        // Perform unrelated metadata update (renaming document) with description = null (omitted)
        DocumentMasterDTO renameDto = DocumentMasterDTO.builder()
                .entityVersion(initial.getEntityVersion())
                .documentName("SWE.2 Software Architecture Design - Renamed")
                .category(initial.getCategory())
                .processId(initial.getProcessId())
                .processGroup("Updated Architecture Group")
                .description(null) // Field omitted in payload
                .build();

        mockMvc.perform(put("/api/admin/dms/documents/" + masterId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(renameDto))
                        .secure(true)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentName").value("SWE.2 Software Architecture Design - Renamed"))
                .andExpect(jsonPath("$.description").value(originalDesc));

        DocumentMaster reloaded = documentMasterRepository.findById(masterId).orElseThrow();
        assertThat(reloaded.getDocumentName()).isEqualTo("SWE.2 Software Architecture Design - Renamed");
        assertThat(reloaded.getDescription()).isEqualTo(originalDesc); // PRESERVED!
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("6. Version replacement must NOT erase master description")
    void testVersionUpload_PreservesMasterDescription() throws Exception {
        // 1. Initial upload with description
        String originalDesc = "Specification description that must survive new versions";
        MockMultipartFile v1File = new MockMultipartFile(
                "file", "Doc_v1.pdf", "application/pdf", "%PDF-1.4 Version 1 Content".getBytes()
        );
        UploadResponseDTO res1 = dmsDocumentService.uploadDocument(
                v1File, "ASPICE PRM", "Quality Assurance", "QA.1", "Quality Assurance",
                "Quality Assurance Manual", "1.0", originalDesc, "v1 remarks", "admin", false
        );
        Long masterId = res1.getDocumentMasterId();
        DocumentMaster initial = documentMasterRepository.findById(masterId).orElseThrow();
        assertThat(initial.getDescription()).isEqualTo(originalDesc);

        // 2. Upload new version v1.1
        MockMultipartFile v2File = new MockMultipartFile(
                "file", "Doc_v2.pdf", "application/pdf", "%PDF-1.4 Version 2 Different Content".getBytes()
        );
        UploadResponseDTO res2 = dmsDocumentService.uploadNewVersion(
                masterId, v2File.getBytes(), "Doc_v2.pdf", "PDF", "application/pdf",
                "1.1", "Uploaded version 1.1 with minor edits", "admin"
        );
        assertThat(res2.isSuccess()).isTrue();
        assertThat(res2.getVersion()).isEqualTo("1.1");

        // 3. Verify master description is STILL intact
        DocumentMaster masterAfterVersion = documentMasterRepository.findById(masterId).orElseThrow();
        assertThat(masterAfterVersion.getCurrentVersion()).isEqualTo("1.1");
        assertThat(masterAfterVersion.getDescription()).isEqualTo(originalDesc);

        // 4. Verify via admin GET endpoint
        mockMvc.perform(get("/api/admin/dms/documents/" + masterId).secure(true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value("1.1"))
                .andExpect(jsonPath("$.description").value(originalDesc));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("7. Legacy remarks backward compatibility -> when description is omitted, remarks is saved to description")
    void testRemarksFallback_WhenDescriptionParamOmitted() throws Exception {
        String remarksAsDesc = "Legacy client provided description in remarks param";
        MockMultipartFile file = new MockMultipartFile(
                "file", "Legacy_Remarks_Doc.pdf", "application/pdf", "%PDF-1.4 Legacy Remarks".getBytes()
        );

        mockMvc.perform(multipart("/api/admin/dms/upload")
                        .file(file)
                        .secure(true)
                        .param("category", "Generic Templates")
                        .param("processId", "GENERIC")
                        .param("processGroup", "Generic Templates")
                        .param("documentName", "Generic Template with Remarks")
                        .param("version", "1.0")
                        .param("remarks", remarksAsDesc) // Only remarks supplied
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        List<DocumentMaster> masters = documentMasterRepository.findAll();
        assertThat(masters).hasSize(1);
        DocumentMaster saved = masters.get(0);
        assertThat(saved.getDescription()).isEqualTo(remarksAsDesc);
    }
}
