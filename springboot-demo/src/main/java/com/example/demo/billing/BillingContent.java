package com.example.demo.billing;
import lombok.Data;
import java.math.BigDecimal;
import java.util.*;

@Data
public class BillingContent {
    private Long version;
    private String requestKey;
    private String companyName;
    private String phone;
    private String customerName;
    /** Factory ledger the user picked; the document is posted there only after it is saved. */
    private Long ledgerId;
    private String documentType = "PURCHASE";
    private String sourceDocumentNumber;
    private String date;
    private String remark;
    /** Readable source evidence; never used as a calculated or confirmed amount. */
    private String sourceNote;
    /** 拍照开单时的原图附件，开单页据此显示原图和每行截图。 */
    private Long sourceAttachmentId;
    private List<Line> lines=new ArrayList<>();
    @Data public static class Line {
        private String name;
        private String specification;
        private String unit;
        private String alias;
        private String remark;
        private BigDecimal quantity;
        private BigDecimal price;
        private BigDecimal amount;
        private String sourceAmount;
        /** 这一行来自原图 OCR 的第几行，用来截图核对。 */
        private Integer sourceRow;
        /** Original recognition text and sourced advice; applying a suggestion is a user action. */
        private String rawName;
        private String rawSpecification;
        private String knowledgeReviewValue;
        private List<SpecificationKnowledgeCatalog.Advice> knowledgeAdvice=new ArrayList<>();
        /** Candidate identity used only for ranking after a confirmed document. */
        private String candidateKey;
        /** Preserved reference details from product memory, product master data, or the local encyclopedia. */
        private String referenceSource;
        private String referenceCategory;
        private List<String> referenceAliases=new ArrayList<>();
        private List<String> unitCandidates=new ArrayList<>();
        private String referenceCaution;
        private boolean remember;
    }
}
