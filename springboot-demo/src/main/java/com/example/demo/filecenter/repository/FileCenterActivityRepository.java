package com.example.demo.filecenter.repository;

import com.example.demo.filecenter.entity.FileCenterActivity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FileCenterActivityRepository extends JpaRepository<FileCenterActivity, Long> {
    List<FileCenterActivity> findByMetadataIdOrderByCreateTimeDesc(Long metadataId);
}
