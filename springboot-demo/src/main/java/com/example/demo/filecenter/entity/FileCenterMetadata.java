package com.example.demo.filecenter.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "file_center_metadata")
@Data
public class FileCenterMetadata {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long operatorId;
    private String sourceKind;
    private Long sourceId;
    private String purposeCode;
    private String relatedType;
    private Long relatedId;
    private String relatedName;
    private String note;
    private Boolean manualUpload;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
