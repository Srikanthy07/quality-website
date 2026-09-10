package com.qualitywebsite.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Authoritative DMS DTO for public and runtime document responses.
 * Maps directly from DocumentMaster + DocumentVersion with no legacy table dependencies.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PublicDocumentDTO {

    private Long masterId;
    private String documentCode;
    private String documentName;
    private String processId;
    private String processName;
    private String processGroup;
    private String category;
    private String description;
    private String version;
    private Long versionId;
    private String fileName;
    private String fileType;
    private String mimeType;
    private Long fileSize;
    private String status;
    private String downloadUrl;
    private LocalDateTime createdDate;
    private LocalDateTime updatedDate;

    // --- Backward compatibility getters for frontend and legacy API callers ---

    @JsonProperty("id")
    public String getId() {
        return (documentCode != null && !documentCode.isBlank())
                ? documentCode
                : (masterId != null ? "DMS-" + masterId : null);
    }

    @JsonProperty("process")
    public String getProcess() {
        return processId;
    }

    @JsonProperty("filePath")
    public String getFilePath() {
        return downloadUrl;
    }

    @JsonProperty("type")
    public String getType() {
        return fileType;
    }
}
