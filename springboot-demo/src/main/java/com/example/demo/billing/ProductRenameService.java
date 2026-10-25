package com.example.demo.billing;

import com.example.demo.entity.FactoryLedgerItem;
import com.example.demo.entity.FactoryLedgerTransaction;
import com.example.demo.repository.FactoryLedgerTransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 商品库的名称和单据里的商品名称是绑定的：商品改名后，所有引用这个商品的单据明细、
 * 已记入账本的明细、库存流水里的名称一起改过来，方便事后统一修正当时临时记的商品名。
 *
 * <p>单据按明细里的“商品编号”（candidateKey = product:ID）认；账本明细没有商品编号，
 * 通过它来源的单据找到，并且只改名称和旧名称一致的那几行。
 * 直接改库而不动单据版本号，正在编辑的草稿不会因为改名而保存冲突。</p>
 */
@Service
@Transactional
public class ProductRenameService {
    private final JdbcTemplate jdbc;
    private final FactoryLedgerTransactionRepository transactions;
    private final ObjectMapper mapper;

    public ProductRenameService(JdbcTemplate jdbc, FactoryLedgerTransactionRepository transactions, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
    }

    /** 返回受影响的单据张数。 */
    public int propagate(Long productId, String oldName, String newName) {
        if (productId == null || !StringUtils.hasText(newName) || newName.equals(oldName)) return 0;
        String key = "product:" + productId;
        int changedDocuments = 0;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, document_number, content_json FROM billing_document WHERE content_json LIKE ?", "%\"" + key + "\"%");
        for (Map<String, Object> row : rows) {
            BillingContent content;
            try { content = mapper.readValue(String.valueOf(row.get("content_json")), BillingContent.class); }
            catch (Exception exception) { continue; }
            if (content.getLines() == null) continue;
            boolean changed = false;
            for (BillingContent.Line line : content.getLines()) {
                if (line != null && key.equals(line.getCandidateKey()) && !newName.equals(line.getName())) {
                    line.setName(newName);
                    changed = true;
                }
            }
            if (!changed) continue;
            try {
                jdbc.update("UPDATE billing_document SET content_json = ? WHERE id = ?", mapper.writeValueAsString(content), row.get("id"));
            } catch (Exception exception) {
                throw new IllegalStateException("单据商品名称同步失败", exception);
            }
            changedDocuments++;
            renameLedgerItems(String.valueOf(row.get("document_number")), oldName, newName);
        }
        try {
            jdbc.update("UPDATE inventory_log SET product_name = ? WHERE product_id = ?", newName, productId);
        } catch (DataAccessException ignored) { /* 没有这张表或这一列时不影响改名 */ }
        try {
            // 开单记忆里的候选商品名也要改，否则搜索会还出旧名字，选中后又重新建一个旧名商品
            // 记忆按名称+规格+单位识别，改名后旧的那条已经失效，直接删掉，下次开单会按新名字重新记
            jdbc.update("DELETE FROM billing_product_memory WHERE product_id = ? OR (lower(trim(name)) = lower(trim(?)) AND lower(trim(coalesce(specification,''))) = (SELECT lower(trim(coalesce(spec,''))) FROM product WHERE id = ?))", productId, oldName, productId);
        } catch (DataAccessException ignored) { /* 同上 */ }
        try {
            jdbc.update("UPDATE product_price_history SET product_name = ? WHERE product_id = ?", newName, productId);
        } catch (DataAccessException ignored) { /* 同上 */ }
        return changedDocuments;
    }

    private void renameLedgerItems(String documentNumber, String oldName, String newName) {
        if (!StringUtils.hasText(documentNumber) || "null".equals(documentNumber)) return;
        for (FactoryLedgerTransaction transaction : transactions.findBySourceAndReceiptNoStartingWith("BILLING", documentNumber)) {
            String receipt = transaction.getReceiptNo();
            if (receipt == null || !(documentNumber.equals(receipt) || receipt.startsWith(documentNumber + "-R"))) continue;
            transaction.loadItems();
            boolean changed = false;
            for (FactoryLedgerItem item : transaction.getItems()) {
                if (item != null && same(item.getProductName(), oldName)) {
                    item.setProductName(newName);
                    changed = true;
                }
            }
            if (changed) {
                transaction.saveItems();
                transactions.save(transaction);
            }
        }
    }

    private static boolean same(String left, String right) {
        String a = left == null ? "" : left.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        String b = right == null ? "" : right.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return a.equals(b);
    }
}
