package com.example.demo.filecenter.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "file_center_activity")
@Data
public class FileCenterActivity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long metadataId;
    private String activityType;
    private String title;
    private String detail;
    private LocalDateTime createTime;
}
