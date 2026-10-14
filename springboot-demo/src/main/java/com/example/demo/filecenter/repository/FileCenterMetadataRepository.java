package com.example.demo.filecenter.repository;

import com.example.demo.filecenter.entity.FileCenterMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FileCenterMetadataRepository extends JpaRepository<FileCenterMetadata, Long> {
    List<FileCenterMetadata> findByOperatorId(Long operatorId);
    Optional<FileCenterMetadata> findByOperatorIdAndSourceKindAndSourceId(Long operatorId, String sourceKind, Long sourceId);
}
