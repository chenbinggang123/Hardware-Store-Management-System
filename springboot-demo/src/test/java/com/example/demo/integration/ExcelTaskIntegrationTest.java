package com.example.demo.integration;

import com.example.demo.agent.service.attachment.AttachmentStorage;
import com.example.demo.excel.repository.ExcelTaskRowRepository;
import com.example.demo.excel.dto.ExcelColumnMappingRequest;
import com.example.demo.excel.dto.ExcelMappingRequest;
import com.example.demo.excel.dto.ExcelRowReviewRequest;
import com.example.demo.excel.dto.ExcelTaskCommitRequest;
import com.example.demo.excel.service.ExcelTaskCommitService;
import com.example.demo.excel.service.ExcelTaskMappingService;
import com.example.demo.excel.service.ExcelTaskService;
import com.example.demo.entity.Product;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.InventoryRepository;
import com.example.demo.repository.InventoryLogRepository;
import org.springframework.web.server.ResponseStatusException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ExcelTaskIntegrationTest {
    @Autowired
    private ExcelTaskService service;

    @Autowired
    private ExcelTaskMappingService mappingService;

    @Autowired
    private ExcelTaskCommitService commitService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryLogRepository inventoryLogRepository;

    @Autowired
    private ExcelTaskRowRepository rowRepository;

    @MockBean
    private AttachmentStorage storage;

    @Test
    void uploadCreatesPreviewableTaskWithoutWritingBusinessData() throws Exception {
        byte[] content;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("需求表");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("商品名称");
            header.createCell(1).setCellValue("规格");
            header.createCell(2).setCellValue("数量");
            var first = sheet.createRow(1);
            first.createCell(0).setCellValue("东成充电电钻");
            first.createCell(1).setCellValue("16V 双电");
            first.createCell(2).setCellValue(2);
            workbook.write(output);
            content = output.toByteArray();
        }
        var file = new MockMultipartFile("file", "买家需求.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);

        var task = service.upload(file, "BUYER_QUOTE", 99L);

        assertThat(task.status()).isEqualTo("READY_FOR_MAPPING");
        assertThat(task.sheetCount()).isEqualTo(1);
        assertThat(task.rowCount()).isEqualTo(1);
        assertThat(task.sheets().get(0).columns()).containsExactly("商品名称", "规格", "数量");

        var page = service.rows(task.id(), task.sheets().get(0).id(), 0, 50, 99L);
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.items().get(0).rowNumber()).isEqualTo(2);
        assertThat(page.items().get(0).cells()).containsExactly("东成充电电钻", "16V 双电", "2");
        assertThat(rowRepository.count()).isGreaterThanOrEqualTo(1);

        assertThatThrownBy(() -> service.get(task.id(), 100L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权访问");
    }

    @Test
    void mappingMatchesBarcodeAndPreviewsPriceWithoutChangingProduct() throws Exception {
        String barcode = "T-" + UUID.randomUUID();
        Product product = product("测试电钻", barcode, "18V", new BigDecimal("80.00"));
        product = productRepository.saveAndFlush(product);
        byte[] content = workbook(new String[]{"条码", "商品", "规格", "进价"},
                new String[]{barcode, "测试电钻", "18V", "92.50"});
        var task = service.upload(new MockMultipartFile("file", "供应商报价.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content),
                "SUPPLIER_PRICE", 99L);
        Long sheetId = task.sheets().get(0).id();

        var summary = mappingService.apply(task.id(), sheetId,
                new ExcelMappingRequest(task.version(), List.of(
                        new ExcelColumnMappingRequest(0, "BARCODE"),
                        new ExcelColumnMappingRequest(1, "PRODUCT_NAME"),
                        new ExcelColumnMappingRequest(2, "SPEC"),
                        new ExcelColumnMappingRequest(3, "UNIT_PRICE"))), 99L);

        assertThat(summary.status()).isEqualTo("READY_FOR_REVIEW");
        assertThat(summary.matchedRows()).isEqualTo(1);
        assertThat(summary.invalidRows()).isZero();
        var row = mappingService.rows(task.id(), sheetId, 0, 20, 99L).items().get(0);
        assertThat(row.matchReason()).isEqualTo("条码精确匹配");
        assertThat(row.actionType()).isEqualTo("UPDATE_COST_PRICE");
        assertThat(row.before()).containsEntry("costPrice", 80.00);
        assertThat(row.after()).containsEntry("costPrice", 92.50);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getCostPrice())
                .isEqualByComparingTo("80.00");
    }

    @Test
    void ambiguousProductNameStaysForHumanReview() throws Exception {
        String name = "同名螺丝-" + UUID.randomUUID();
        productRepository.save(product(name, "A-" + UUID.randomUUID(), "M6", BigDecimal.ONE));
        productRepository.save(product(name, "B-" + UUID.randomUUID(), "M8", BigDecimal.ONE));
        byte[] content = workbook(new String[]{"商品名称", "数量"}, new String[]{name, "20"});
        var task = service.upload(new MockMultipartFile("file", "买家需求.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content),
                "BUYER_QUOTE", 99L);
        Long sheetId = task.sheets().get(0).id();

        var summary = mappingService.apply(task.id(), sheetId,
                new ExcelMappingRequest(task.version(), List.of(
                        new ExcelColumnMappingRequest(0, "PRODUCT_NAME"),
                        new ExcelColumnMappingRequest(1, "QUANTITY"))), 99L);

        assertThat(summary.needsReviewRows()).isEqualTo(1);
        var row = mappingService.rows(task.id(), sheetId, 0, 20, 99L).items().get(0);
        assertThat(row.matchStatus()).isEqualTo("NEEDS_REVIEW");
        assertThat(row.candidates()).hasSize(2);
        assertThat(row.matchedProductId()).isNull();
    }

    @Test
    void mappingRejectsMissingRequiredPriceAndStaleVersion() throws Exception {
        byte[] content = workbook(new String[]{"商品名称"}, new String[]{"待报价商品"});
        var task = service.upload(new MockMultipartFile("file", "供应商报价.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content),
                "SUPPLIER_PRICE", 99L);
        Long sheetId = task.sheets().get(0).id();

        assertThatThrownBy(() -> mappingService.apply(task.id(), sheetId,
                new ExcelMappingRequest(task.version(), List.of(
                        new ExcelColumnMappingRequest(0, "PRODUCT_NAME"))), 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("单价字段");
        assertThatThrownBy(() -> mappingService.apply(task.id(), sheetId,
                new ExcelMappingRequest(task.version() + 1, List.of(
                        new ExcelColumnMappingRequest(0, "PRODUCT_NAME"))), 99L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("刷新后重试");
    }

    @Test
    void manualSelectionResolvesAmbiguousRow() throws Exception {
        String name = "人工匹配螺母-" + UUID.randomUUID();
        Product selected = productRepository.saveAndFlush(product(name, "S-" + UUID.randomUUID(), "M6", BigDecimal.ONE));
        productRepository.save(product(name, "O-" + UUID.randomUUID(), "M8", BigDecimal.ONE));
        byte[] content = workbook(new String[]{"商品名称", "数量"}, new String[]{name, "5"});
        var task = service.upload(new MockMultipartFile("file", "盘点.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content),
                "INVENTORY_COUNT", 99L);
        Long sheetId = task.sheets().get(0).id();
        var mapped = mappingService.apply(task.id(), sheetId,
                new ExcelMappingRequest(task.version(), List.of(
                        new ExcelColumnMappingRequest(0, "PRODUCT_NAME"),
                        new ExcelColumnMappingRequest(1, "QUANTITY"))), 99L);
        var reviewRow = mappingService.rows(task.id(), sheetId, 0, 20, 99L).items().get(0);

        commitService.reviewRow(task.id(), sheetId, reviewRow.id(),
                new ExcelRowReviewRequest(mapped.version(), "SELECT_PRODUCT", selected.getId(), null), 99L);

        var updated = mappingService.rows(task.id(), sheetId, 0, 20, 99L).items().get(0);
        assertThat(updated.matchStatus()).isEqualTo("MATCHED");
        assertThat(updated.matchedProductId()).isEqualTo(selected.getId());
        assertThat(updated.matchReason()).isEqualTo("人工选择商品");
    }

    @Test
    void commitUpdatesSupplierPriceOnceAndReturnsPriorIdempotentResult() throws Exception {
        String barcode = "C-" + UUID.randomUUID();
        Product product = productRepository.saveAndFlush(
                product("提交测试电钻", barcode, "20V", new BigDecimal("100.00")));
        byte[] content = workbook(new String[]{"条码", "新进价"}, new String[]{barcode, "115.80"});
        var task = service.upload(new MockMultipartFile("file", "价格更新.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content),
                "SUPPLIER_PRICE", 99L);
        Long sheetId = task.sheets().get(0).id();
        var mapped = mappingService.apply(task.id(), sheetId,
                new ExcelMappingRequest(task.version(), List.of(
                        new ExcelColumnMappingRequest(0, "BARCODE"),
                        new ExcelColumnMappingRequest(1, "UNIT_PRICE"))), 99L);

        var validation = commitService.validate(task.id(), mapped.version(), 99L);
        assertThat(validation.valid()).isTrue();
        String key = "excel-commit-" + UUID.randomUUID();
        var first = commitService.commit(task.id(), new ExcelTaskCommitRequest(mapped.version(), key), 99L);
        var repeated = commitService.commit(task.id(), new ExcelTaskCommitRequest(mapped.version(), key), 99L);

        assertThat(first.commitId()).isEqualTo(repeated.commitId());
        assertThat(first.updatedProducts()).isEqualTo(1);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getCostPrice())
                .isEqualByComparingTo("115.80");
        assertThat(service.get(task.id(), 99L).status()).isEqualTo("COMMITTED");
    }

    @Test
    void commitStopsWhenBusinessDataChangedAfterPreview() throws Exception {
        String barcode = "D-" + UUID.randomUUID();
        Product product = productRepository.saveAndFlush(
                product("并发测试电钻", barcode, "12V", new BigDecimal("50.00")));
        byte[] content = workbook(new String[]{"条码", "新进价"}, new String[]{barcode, "60.00"});
        var task = service.upload(new MockMultipartFile("file", "价格更新.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content),
                "SUPPLIER_PRICE", 99L);
        Long sheetId = task.sheets().get(0).id();
        var mapped = mappingService.apply(task.id(), sheetId,
                new ExcelMappingRequest(task.version(), List.of(
                        new ExcelColumnMappingRequest(0, "BARCODE"),
                        new ExcelColumnMappingRequest(1, "UNIT_PRICE"))), 99L);
        product.setCostPrice(new BigDecimal("55.00"));
        productRepository.saveAndFlush(product);

        assertThatThrownBy(() -> commitService.commit(task.id(),
                new ExcelTaskCommitRequest(mapped.version(), "excel-drift-" + UUID.randomUUID()), 99L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("重新生成预览");
        assertThat(productRepository.findById(product.getId()).orElseThrow().getCostPrice())
                .isEqualByComparingTo("55.00");
    }

    @Test
    void inventoryCountCommitUpdatesInventoryProductAndAuditLogTogether() throws Exception {
        String barcode = "I-" + UUID.randomUUID();
        Product product = productRepository.saveAndFlush(
                product("盘点测试商品", barcode, "标准", BigDecimal.ONE));
        byte[] content = workbook(new String[]{"条码", "实际数量"}, new String[]{barcode, "7"});
        var task = service.upload(new MockMultipartFile("file", "盘点.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content),
                "INVENTORY_COUNT", 99L);
        Long sheetId = task.sheets().get(0).id();
        var mapped = mappingService.apply(task.id(), sheetId,
                new ExcelMappingRequest(task.version(), List.of(
                        new ExcelColumnMappingRequest(0, "BARCODE"),
                        new ExcelColumnMappingRequest(1, "QUANTITY"))), 99L);

        var committed = commitService.commit(task.id(), new ExcelTaskCommitRequest(
                mapped.version(), "inventory-" + UUID.randomUUID()), 99L);

        assertThat(committed.inventoryChanges()).isEqualTo(1);
        assertThat(inventoryRepository.findByProductId(product.getId()).orElseThrow().getQuantity()).isEqualTo(7);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock()).isEqualTo(7);
        assertThat(inventoryLogRepository.findByProductIdOrderByCreateTimeDesc(product.getId()))
                .anySatisfy(log -> {
                    assertThat(log.getBeforeQuantity()).isEqualTo(10);
                    assertThat(log.getAfterQuantity()).isEqualTo(7);
                    assertThat(log.getOperatorId()).isEqualTo(99L);
                });
    }

    @Test
    void productImportCommitCreatesProductAndInitialInventory() throws Exception {
        String barcode = "N-" + UUID.randomUUID();
        byte[] content = workbook(new String[]{"商品名称", "条码", "规格", "单位", "进价"},
                new String[]{"新商品", barcode, "M10", "盒", "12.30"});
        var task = service.upload(new MockMultipartFile("file", "新品.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content),
                "PRODUCT_IMPORT", 99L);
        Long sheetId = task.sheets().get(0).id();
        var mapped = mappingService.apply(task.id(), sheetId,
                new ExcelMappingRequest(task.version(), List.of(
                        new ExcelColumnMappingRequest(0, "PRODUCT_NAME"),
                        new ExcelColumnMappingRequest(1, "BARCODE"),
                        new ExcelColumnMappingRequest(2, "SPEC"),
                        new ExcelColumnMappingRequest(3, "UNIT"),
                        new ExcelColumnMappingRequest(4, "UNIT_PRICE"))), 99L);

        assertThat(mapped.readyToCreateRows()).isEqualTo(1);
        var committed = commitService.commit(task.id(), new ExcelTaskCommitRequest(
                mapped.version(), "new-product-" + UUID.randomUUID()), 99L);

        Product created = productRepository.findByBarcode(barcode).orElseThrow();
        assertThat(committed.createdProducts()).isEqualTo(1);
        assertThat(created.getName()).isEqualTo("新商品");
        assertThat(created.getCostPrice()).isEqualByComparingTo("12.30");
        assertThat(inventoryRepository.findByProductId(created.getId()).orElseThrow().getQuantity()).isZero();
    }

    private Product product(String name, String barcode, String spec, BigDecimal costPrice) {
        Product product = new Product();
        product.setName(name);
        product.setBarcode(barcode);
        product.setSpec(spec);
        product.setUnit("个");
        product.setRetailPrice(BigDecimal.ZERO);
        product.setWholesalePrice(BigDecimal.ZERO);
        product.setOldCustomerPrice(BigDecimal.ZERO);
        product.setCostPrice(costPrice);
        product.setStock(10);
        product.setStatus(1);
        product.setCreateTime(LocalDateTime.now());
        return product;
    }

    private byte[] workbook(String[] headers, String[] values) throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("数据");
            var header = sheet.createRow(0);
            for (int index = 0; index < headers.length; index++) header.createCell(index).setCellValue(headers[index]);
            var row = sheet.createRow(1);
            for (int index = 0; index < values.length; index++) row.createCell(index).setCellValue(values[index]);
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
