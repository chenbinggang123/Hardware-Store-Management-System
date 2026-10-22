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
