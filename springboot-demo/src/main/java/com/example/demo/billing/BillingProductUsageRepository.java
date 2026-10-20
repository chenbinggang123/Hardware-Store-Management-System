package com.example.demo.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface BillingProductUsageRepository extends JpaRepository<BillingProductUsage,Long> {
    Optional<BillingProductUsage> findByOperatorIdAndCandidateKey(Long operatorId,String candidateKey);
    List<BillingProductUsage> findByOperatorIdAndCandidateKeyIn(Long operatorId,Collection<String> candidateKeys);
    List<BillingProductUsage> findTop5ByOperatorIdOrderByUseCountDescLastUsedTimeDesc(Long operatorId);
}
