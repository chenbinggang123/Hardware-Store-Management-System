package com.example.demo.billing;
import com.example.demo.common.ApiResponse;
import com.example.demo.config.AuthInterceptor;
import com.example.demo.security.AuthSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/billing")
public class BillingController {
    private final BillingService service;
    private final BillingImportService importer;
    private final BillingLedgerService ledger;
    public BillingController(BillingService service,BillingImportService importer,BillingLedgerService ledger){this.service=service;this.importer=importer;this.ledger=ledger;}
    @GetMapping("/imports/{outputId}") public ApiResponse<?> importPreview(@PathVariable Long outputId,HttpServletRequest r){return ApiResponse.ok(importer.preview(owner(r),outputId));}
    @GetMapping("/products") public ApiResponse<?> search(@RequestParam(defaultValue="") String keyword,HttpServletRequest r){return ApiResponse.ok(service.candidates(owner(r),keyword));}
    @GetMapping("/records") public ApiResponse<?> records(@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String documentType,@RequestParam(defaultValue="") String factory,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest r){return ApiResponse.ok(ledger.records(owner(r),keyword,status,documentType,factory,page,size));}
    public record DraftDeletion(List<Long> ids){}
    @DeleteMapping("/documents/drafts") public ApiResponse<?> deleteDrafts(@RequestBody DraftDeletion body,HttpServletRequest r){var done=ledger.deleteDraftsAndPhotos(owner(r),body.ids());return ApiResponse.ok(Map.of("deleted",done.deleted(),"cloudFiles",done.cloudFiles()));}
    @GetMapping("/documents") public ApiResponse<?> list(HttpServletRequest r){return ApiResponse.ok(service.list(owner(r)));}
    @PostMapping("/documents") public ApiResponse<?> create(@RequestBody BillingContent c,HttpServletRequest r){return ApiResponse.ok(service.save(owner(r),null,c));}
    @GetMapping("/documents/{id}") public ApiResponse<?> get(@PathVariable Long id,HttpServletRequest r){return ApiResponse.ok(service.get(owner(r),id));}
    @PutMapping("/documents/{id}") public ApiResponse<?> save(@PathVariable Long id,@RequestBody BillingContent c,HttpServletRequest r){return ApiResponse.ok(service.save(owner(r),id,c));}
    @DeleteMapping("/documents/{id}") public ApiResponse<?> delete(@PathVariable Long id,HttpServletRequest r){return ApiResponse.ok(Map.of("deleted",true,"cloudFiles",ledger.deleteDocumentAndPhoto(owner(r),id)));}
    public record Confirmation(Long version){}
    @DeleteMapping("/ledgers/{ledgerId}/transactions/{transactionId}/document") public ApiResponse<?> deleteFromLedger(@PathVariable Long ledgerId,@PathVariable Long transactionId,HttpServletRequest r){return ApiResponse.ok(Map.of("deleted",true,"cloudFiles",ledger.deleteFromLedgerAndPhoto(owner(r),ledgerId,transactionId)));}
    @PostMapping("/documents/{id}/confirm") public ApiResponse<?> confirm(@PathVariable Long id,@RequestBody Confirmation c,HttpServletRequest r){return ApiResponse.ok(service.confirm(owner(r),id,c.version()));}
    public record LedgerPosting(Long ledgerId){}
    public record VoidRequest(String reason){}
    @PostMapping("/documents/{id}/reopen") public ApiResponse<?> reopen(@PathVariable Long id,HttpServletRequest r){return ApiResponse.ok(ledger.reopen(owner(r),id));}
    @PostMapping("/documents/{id}/void") public ApiResponse<?> voidDocument(@PathVariable Long id,@RequestBody(required=false) VoidRequest v,HttpServletRequest r){return ApiResponse.ok(ledger.voidDocument(owner(r),id,v==null?null:v.reason()));}
    @GetMapping("/documents/{id}/ledger") public ApiResponse<?> ledgerStatus(@PathVariable Long id,HttpServletRequest r){return ApiResponse.ok(ledger.status(owner(r),id));}
    @PostMapping("/documents/{id}/ledger") public ApiResponse<?> postToLedger(@PathVariable Long id,@RequestBody(required=false) LedgerPosting p,HttpServletRequest r){return ApiResponse.ok(ledger.post(owner(r),id,p==null?null:p.ledgerId()));}
    @GetMapping("/procurements/unposted") public ApiResponse<?> unpostedProcurements(HttpServletRequest r){return ApiResponse.ok(ledger.unpostedProcurements(owner(r)));}
    @PostMapping("/procurements/backfill") public ApiResponse<?> backfillProcurements(HttpServletRequest r){return ApiResponse.ok(ledger.backfillProcurements(owner(r)));}
    @PostMapping("/documents/{id}/excel") public ApiResponse<?> excel(@PathVariable Long id,HttpServletRequest r){return ApiResponse.ok(service.excel(owner(r),id));}
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public org.springframework.http.ResponseEntity<?> concurrentMemory(){return org.springframework.http.ResponseEntity.status(409).body(ApiResponse.fail("同一商品资料正在另一张单据中保存，请重试本次确认；本次修改未提交"));}
    private Long owner(HttpServletRequest r){Object a=r.getAttribute(AuthInterceptor.AUTH_SESSION_ATTRIBUTE);if(a instanceof AuthSession s&&s.getUser()!=null)return s.getUser().getId();throw new IllegalArgumentException("请先登录");}
}
