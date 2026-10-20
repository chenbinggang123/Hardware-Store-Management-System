package com.example.demo.billing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.util.*;
public interface BillingMemoryRepository extends JpaRepository<BillingProductMemory,Long> {
    Optional<BillingProductMemory> findByOperatorIdAndFingerprint(Long operatorId,String fingerprint);
    @Query("select m from BillingProductMemory m where m.operatorId=:owner and (lower(m.name) like lower(concat('%',:q,'%')) or lower(m.alias) like lower(concat('%',:q,'%')) or lower(m.specification) like lower(concat('%',:q,'%'))) order by m.updateTime desc")
    List<BillingProductMemory> search(@Param("owner") Long owner,@Param("q") String q,Pageable page);
}
