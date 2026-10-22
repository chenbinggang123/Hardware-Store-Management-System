package com.example.demo.billing;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface BillingDocumentRepository extends JpaRepository<BillingDocument,Long> {
    List<BillingDocument> findByOperatorIdAndStatusIn(Long operatorId, Collection<String> statuses);
    @Query(value="select * from billing_document d where d.operator_id=:owner and (:status='' or d.status=:status) and (:keyword='' or d.document_number like concat('%',:keyword,'%') or d.content_json like concat('%',:keyword,'%')) and (:documentType='' or coalesce(json_unquote(json_extract(d.content_json,'$.documentType')),'PURCHASE')=:documentType) and (:factory='' or json_unquote(json_extract(d.content_json,'$.customerName'))=:factory) order by d.update_time desc,d.id desc",
            countQuery="select count(*) from billing_document d where d.operator_id=:owner and (:status='' or d.status=:status) and (:keyword='' or d.document_number like concat('%',:keyword,'%') or d.content_json like concat('%',:keyword,'%')) and (:documentType='' or coalesce(json_unquote(json_extract(d.content_json,'$.documentType')),'PURCHASE')=:documentType) and (:factory='' or json_unquote(json_extract(d.content_json,'$.customerName'))=:factory)",nativeQuery=true)
    org.springframework.data.domain.Page<BillingDocument> searchFiltered(@Param("owner") Long owner,@Param("keyword") String keyword,@Param("status") String status,@Param("documentType") String documentType,@Param("factory") String factory,org.springframework.data.domain.Pageable pageable);
    Optional<BillingDocument> findByDocumentNumber(String documentNumber);
    List<BillingDocument> findTop5ByOperatorIdOrderByUpdateTimeDesc(Long operatorId);
    Optional<BillingDocument> findByOperatorIdAndRequestKey(Long operatorId,String requestKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from BillingDocument d where d.id=:id and d.operatorId=:owner")
    Optional<BillingDocument> lockOwned(@Param("id") Long id,@Param("owner") Long owner);
    /** 某月的单据：单号形如 KD-20261021-9，按保存日期归月。 */
    List<BillingDocument> findByStatusInAndDocumentNumberStartingWith(Collection<String> statuses,String numberPrefix);
    @Query("select d from BillingDocument d where d.operatorId=:owner and (:keyword='' or d.documentNumber like concat('%',:keyword,'%') or d.contentJson like concat('%',:keyword,'%')) order by d.updateTime desc")
    org.springframework.data.domain.Page<BillingDocument> search(@Param("owner") Long owner,@Param("keyword") String keyword,org.springframework.data.domain.Pageable pageable);
    @Query("select d from BillingDocument d where d.operatorId=:owner and d.status=:status and (:keyword='' or d.documentNumber like concat('%',:keyword,'%') or d.contentJson like concat('%',:keyword,'%')) order by d.updateTime desc, d.id desc")
    org.springframework.data.domain.Page<BillingDocument> searchByStatus(@Param("owner") Long owner,@Param("keyword") String keyword,@Param("status") String status,org.springframework.data.domain.Pageable pageable);
}
