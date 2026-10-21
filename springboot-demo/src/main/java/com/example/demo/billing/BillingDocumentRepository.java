package com.example.demo.billing;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface BillingDocumentRepository extends JpaRepository<BillingDocument,Long> {
    List<BillingDocument> findTop5ByOperatorIdOrderByUpdateTimeDesc(Long operatorId);
    Optional<BillingDocument> findByOperatorIdAndRequestKey(Long operatorId,String requestKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from BillingDocument d where d.id=:id and d.operatorId=:owner")
    Optional<BillingDocument> lockOwned(@Param("id") Long id,@Param("owner") Long owner);
    /** 某月的单据：单号形如 KD-20261021-9，按保存日期归月。 */
    List<BillingDocument> findByStatusInAndDocumentNumberStartingWith(Collection<String> statuses,String numberPrefix);
    @Query("select d from BillingDocument d where d.operatorId=:owner and (:keyword='' or d.documentNumber like concat('%',:keyword,'%') or d.contentJson like concat('%',:keyword,'%')) order by d.updateTime desc")
    org.springframework.data.domain.Page<BillingDocument> search(@Param("owner") Long owner,@Param("keyword") String keyword,org.springframework.data.domain.Pageable pageable);
}
