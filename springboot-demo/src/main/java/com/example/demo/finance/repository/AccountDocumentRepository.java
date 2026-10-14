package com.example.demo.finance.repository;

import com.example.demo.finance.entity.AccountDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountDocumentRepository extends JpaRepository<AccountDocument, Long> {
    List<AccountDocument> findByOperatorIdOrderByDocumentDateDescIdDesc(Long operatorId);
    Optional<AccountDocument> findByIdAndOperatorId(Long id, Long operatorId);
    Optional<AccountDocument> findByOperatorIdAndBusinessSourceTypeAndBusinessSourceId(Long operatorId, String businessSourceType, Long businessSourceId);
    List<AccountDocument> findByOperatorIdAndDirectionAndCounterpartyTypeAndCounterpartyIdAndStatusNotOrderByDocumentDateAscIdAsc(
            Long operatorId, String direction, String counterpartyType, Long counterpartyId, String status);
}
