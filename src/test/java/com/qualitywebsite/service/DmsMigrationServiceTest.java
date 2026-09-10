package com.qualitywebsite.service;

import com.qualitywebsite.entity.DocumentEntity;
import com.qualitywebsite.entity.DocumentMaster;
import com.qualitywebsite.entity.DocumentVersion;
import com.qualitywebsite.repository.DocumentMasterRepository;
import com.qualitywebsite.repository.DocumentRepository;
import com.qualitywebsite.repository.DocumentVersionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
public class DmsMigrationServiceTest {

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

    @Test
    @DisplayName("Verify full DMS migration populates DocumentMaster and DocumentVersion with valid file bytes")
    void testFullDmsMigrationSuccess() {
        dataInitializationService.seedDocuments();
        dmsMigrationService.migrateToDatabaseStorage();

        List<DocumentEntity> entities = documentRepository.findAll();
        assertThat(entities).isNotEmpty();

        List<DocumentMaster> masters = documentMasterRepository.findAll();
        assertThat(masters).hasSameSizeAs(entities);

        for (DocumentMaster master : masters) {
            Optional<DocumentVersion> versionOpt = documentVersionRepository.findByDocumentMasterIdAndIsLatestTrue(master.getId());
            assertThat(versionOpt).as("DocumentMaster %s must have a latest DocumentVersion", master.getDocumentCode()).isPresent();

            DocumentVersion version = versionOpt.get();
            assertThat(version.getFileData())
                    .as("DocumentVersion fileData for %s must not be null", master.getDocumentCode())
                    .isNotNull();
            assertThat(version.getFileData().length)
                    .as("DocumentVersion fileData for %s must not be empty", master.getDocumentCode())
                    .isGreaterThan(0);
            assertThat(version.getFileSize()).isEqualTo((long) version.getFileData().length);
            assertThat(version.getChecksum()).isNotBlank();
        }
    }

    @Test
    @DisplayName("Verify idempotency: re-running migration does not duplicate records or alter valid file data")
    void testMigrationIdempotency() {
        dataInitializationService.seedDocuments();
        dmsMigrationService.migrateToDatabaseStorage();

        long initialMasterCount = documentMasterRepository.count();
        long initialVersionCount = documentVersionRepository.count();
        assertThat(initialMasterCount).isGreaterThan(0);

        // Run migration a second time
        dmsMigrationService.migrateToDatabaseStorage();

        assertThat(documentMasterRepository.count()).isEqualTo(initialMasterCount);
        assertThat(documentVersionRepository.count()).isEqualTo(initialVersionCount);
    }

    @Test
    @DisplayName("Verify restoration of missing fileData when version has null or empty fileData")
    void testRestorationOfMissingFileData() {
        dataInitializationService.seedDocuments();
        dmsMigrationService.migrateToDatabaseStorage();

        List<DocumentMaster> masters = documentMasterRepository.findAll();
        assertThat(masters).isNotEmpty();

        // Pick one document and simulate lost fileData
        DocumentMaster targetMaster = masters.get(0);
        DocumentVersion version = documentVersionRepository.findByDocumentMasterIdAndIsLatestTrue(targetMaster.getId()).orElseThrow();
        byte[] originalBytes = version.getFileData();
        assertThat(originalBytes).isNotNull();

        // Set fileData to empty byte array and clear size/checksum
        version.setFileData(new byte[0]);
        version.setFileSize(0L);
        documentVersionRepository.saveAndFlush(version);

        // Verify it was emptied
        DocumentVersion emptied = documentVersionRepository.findById(version.getId()).orElseThrow();
        assertThat(emptied.getFileData()).isEmpty();

        // Re-run migration
        dmsMigrationService.migrateToDatabaseStorage();

        // Verify fileData was restored
        DocumentVersion restored = documentVersionRepository.findById(version.getId()).orElseThrow();
        assertThat(restored.getFileData()).isNotEmpty();
        assertThat(restored.getFileData()).isEqualTo(originalBytes);
        assertThat(restored.getFileSize()).isEqualTo((long) originalBytes.length);
    }

    @Test
    @DisplayName("Verify that single document migration with existing valid fileData is untouched")
    void testExistingValidFileDataIsUntouched() {
        dataInitializationService.seedDocuments();
        dmsMigrationService.migrateToDatabaseStorage();

        DocumentMaster master = documentMasterRepository.findAll().get(0);
        DocumentVersion version = documentVersionRepository.findByDocumentMasterIdAndIsLatestTrue(master.getId()).orElseThrow();
        byte[] existingBytes = version.getFileData();

        DocumentEntity legacy = documentRepository.findById(master.getDocumentCode()).orElseThrow();
        DmsMigrationService.MigrationResult result = dmsMigrationService.migrateSingleDocumentWithResult(legacy, null);

        assertThat(result.outcome()).isEqualTo(DmsMigrationService.MigrationOutcome.ALREADY_VALID);
        DocumentVersion after = documentVersionRepository.findById(version.getId()).orElseThrow();
        assertThat(after.getFileData()).isEqualTo(existingBytes);
    }

    @Test
    @DisplayName("Verify that DataInitializationService preserves exact relative physical path in DocumentEntity.filePath")
    void testDataInitializationPreservesExactPhysicalPath() {
        dataInitializationService.seedDocuments();

        List<DocumentEntity> entities = documentRepository.findAll();
        assertThat(entities).isNotEmpty();

        // Verify that entities have mixed-case physical paths as expected from filesystem
        boolean hasUppercaseDir = entities.stream()
                .map(DocumentEntity::getFilePath)
                .filter(p -> p != null)
                .anyMatch(p -> p.contains("/MAN/") || p.contains("/SYS/") || p.contains("/SWE/") || p.contains("/SUP/"));
        assertThat(hasUppercaseDir).as("DocumentEntity.filePath should preserve exact filesystem casing (e.g., /MAN/, /SYS/)").isTrue();

        // Verify that every entity's filePath resolves to real bytes
        for (DocumentEntity entity : entities) {
            byte[] bytes = dmsMigrationService.readPhysicalFileBytes(entity.getFilePath());
            assertThat(bytes).as("Physical file bytes for %s at path %s must be resolvable", entity.getId(), entity.getFilePath()).isNotNull();
            assertThat(bytes.length).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("Verify DmsMigrationService resolves exact physical paths, lowercase paths, and legacy aliases")
    void testDmsMigrationServicePathResolution() {
        // 1. Exact physical path
        String exactPath = "documents/aspice-prm/MAN/man3/0800_ProjectName_Communication_Management_Plan.docx";
        byte[] exactBytes = dmsMigrationService.readPhysicalFileBytes(exactPath);
        assertThat(exactBytes).as("Exact physical path must resolve").isNotNull();
        assertThat(exactBytes.length).isGreaterThan(0);

        // 2. Lowercase path (case-insensitive resolution)
        String lowercasePath = "documents/aspice-prm/man/man3/0800_projectname_communication_management_plan.docx";
        byte[] lowerBytes = dmsMigrationService.readPhysicalFileBytes(lowercasePath);
        assertThat(lowerBytes).as("Lowercase path must resolve via case-insensitive resolver").isNotNull();
        assertThat(lowerBytes).isEqualTo(exactBytes);

        // 3. Legacy alias: documents/manual/... -> documents/aspice-prm/MAN/...
        String manualAliasPath = "documents/manual/man3/0800_ProjectName_Communication_Management_Plan.docx";
        byte[] aliasBytes = dmsMigrationService.readPhysicalFileBytes(manualAliasPath);
        assertThat(aliasBytes).as("Legacy alias 'documents/manual/' must resolve").isNotNull();
        assertThat(aliasBytes).isEqualTo(exactBytes);

        // 4. Legacy alias with lowercase: documents/manual/man3/0800_projectname_...
        String manualLowerAlias = "documents/manual/man3/0800_projectname_communication_management_plan.docx";
        byte[] aliasLowerBytes = dmsMigrationService.readPhysicalFileBytes(manualLowerAlias);
        assertThat(aliasLowerBytes).as("Legacy alias + lowercase must resolve").isNotNull();
        assertThat(aliasLowerBytes).isEqualTo(exactBytes);

        // 5. Legacy alias for supplier: documents/supplier/...
        String supplierAlias = "documents/supplier/sup1/ASPICE_Assessment_Checklist/ASPICE v3.1 vs v4.0.xlsx";
        byte[] supBytes = dmsMigrationService.readPhysicalFileBytes(supplierAlias);
        assertThat(supBytes).as("Legacy alias 'documents/supplier/' must resolve").isNotNull();
        assertThat(supBytes.length).isGreaterThan(0);

        // 6. Legacy alias for software: documents/software/...
        String softwareAlias = "documents/software/swe1/1700_ProjectName_RMP_1.0_Reviewed.docx";
        byte[] sweBytes = dmsMigrationService.readPhysicalFileBytes(softwareAlias);
        assertThat(sweBytes).as("Legacy alias 'documents/software/' must resolve").isNotNull();
        assertThat(sweBytes.length).isGreaterThan(0);

        // 7. Legacy alias for system: documents/system/...
        String systemAlias = "documents/system/sys2/1700_ProjectName_RMP_1.0_Reviewed.docx";
        byte[] sysBytes = dmsMigrationService.readPhysicalFileBytes(systemAlias);
        assertThat(sysBytes).as("Legacy alias 'documents/system/' must resolve").isNotNull();
        assertThat(sysBytes.length).isGreaterThan(0);
    }
}
