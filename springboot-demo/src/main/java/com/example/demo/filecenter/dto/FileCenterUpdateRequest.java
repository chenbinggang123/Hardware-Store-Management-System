package com.example.demo.filecenter.dto;

import lombok.Data;

@Data
public class FileCenterUpdateRequest {
    private String purposeCode;
    private String relatedType;
    private Long relatedId;
    private String relatedName;
    private String note;
    private Boolean manualUpload;
}
