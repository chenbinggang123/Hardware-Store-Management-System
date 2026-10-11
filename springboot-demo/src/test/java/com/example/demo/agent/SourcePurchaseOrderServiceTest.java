package com.example.demo.agent;

import com.example.demo.agent.dto.SourcePurchaseOrderRequest;
import com.example.demo.agent.entity.AgentAttachment;
import com.example.demo.agent.entity.SourcePurchaseDocument;
import com.example.demo.agent.repository.AgentAttachmentRepository;
import com.example.demo.agent.repository.SourcePurchaseDocumentRepository;
import com.example.demo.agent.service.SourcePurchaseOrderService;
import com.example.demo.entity.Product;
import com.example.demo.entity.PurchaseOrder;
import com.example.demo.entity.Supplier;
import com.example.demo.repository.OperationLogRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.SupplierRepository;
import com.example.demo.service.PurchaseOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SourcePurchaseOrderServiceTest {
    private SourcePurchaseDocumentRepository documentRepository;
    private PurchaseOrderService purchaseOrderService;
    private SourcePurchaseOrderService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(SourcePurchaseDocumentRepository.class);
        AgentAttachmentRepository attachmentRepository = mock(AgentAttachmentRepository.class);
        SupplierRepository supplierRepository = mock(SupplierRepository.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        purchaseOrderService = mock(PurchaseOrderService.class);
        OperationLogRepository operationLogRepository = mock(OperationLogRepository.class);
        service = new SourcePurchaseOrderService(
                documentRepository, attachmentRepository, supplierRepository, productRepository,
                purchaseOrderService, operationLogRepository);

        AgentAttachment attachment = new AgentAttachment();
        attachment.setId(8L);
        attachment.setRunId(51L);
        attachment.setOperatorId(7L);
        attachment.setOriginalName("厂商出库单.png");
        attachment.setParseStatus("PARSED");
        when(attachmentRepository.findByRunIdOrderByCreateTimeAsc(51L)).thenReturn(List.of(attachment));

        Supplier supplier = new Supplier();
        supplier.setId(3L);
        supplier.setName("慈溪市东量五金有限公司");
        when(supplierRepository.findAll()).thenReturn(List.of(supplier));

        Product product = new Product();
        product.setId(9L);
        product.setName("直导柱");
        product.setSpec("Φ16*90");
        product.setBarcode("DZ-1690");
        when(productRepository.findAll()).thenReturn(List.of(product));
        when(documentRepository.save(any(SourcePurchaseDocument.class))).thenAnswer(invocation -> {
            SourcePurchaseDocument value = invocation.getArgument(0);
            value.setId(15L);
            return value;
        });
        when(purchaseOrderService.savePurchaseOrder(any(PurchaseOrder.class))).thenAnswer(invocation -> {
            PurchaseOrder value = invocation.getArgument(0);
            value.setId(20L);
            value.setOrderNumber("PO-20");
            return value;
        });
    }

    @Test
    void previewsAndSavesMatchedSourceOrderWithOriginalAttachment() {
        SourcePurchaseOrderRequest request = request();

        Map<String, Object> preview = service.preview(51L, 7L, request);
        assertEquals(new BigDecimal("194.40"), preview.get("calculatedTotalAmount"));
        assertEquals(true, preview.get("canCreatePurchaseOrder"));
        assertTrue(((List<?>) preview.get("warnings")).isEmpty());

        Map<String, Object> saved = service.save(51L, 7L, request);
        assertEquals(15L, saved.get("id"));
        assertEquals(20L, saved.get("purchaseOrderId"));
        assertEquals(8L, saved.get("attachmentId"));

        ArgumentCaptor<PurchaseOrder> purchaseCaptor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(purchaseOrderService).savePurchaseOrder(purchaseCaptor.capture());
        assertEquals(3L, purchaseCaptor.getValue().getSupplierId());
        assertEquals(9L, purchaseCaptor.getValue().getItems().get(0).getProductId());
    }

    @Test
    void rejectsDuplicateSupplierOrderNumber() {
        SourcePurchaseOrderRequest request = request();
        when(documentRepository.findBySupplierNameIgnoreCaseAndSourceOrderNo(
                "慈溪市东量五金有限公司", "XS-001")).thenReturn(Optional.of(new SourcePurchaseDocument()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.preview(51L, 7L, request));

        assertEquals("这家供应商的订单号已经保存过，请勿重复登记", error.getMessage());
    }

    private SourcePurchaseOrderRequest request() {
        SourcePurchaseOrderRequest request = new SourcePurchaseOrderRequest();
        request.setSupplierName("慈溪市东量五金有限公司");
        request.setSourceOrderNo("XS-001");
        request.setOrderDate(LocalDate.of(2026, 9, 29));
        request.setDeclaredTotalAmount(new BigDecimal("194.40"));
        request.setReceiveStock(false);
        SourcePurchaseOrderRequest.Item item = new SourcePurchaseOrderRequest.Item();
        item.setLineNo(1);
        item.setBarcode("DZ-1690");
        item.setProductName("直导柱");
        item.setSpecification("Φ16*90");
        item.setUnit("支");
        item.setQuantity(new BigDecimal("40"));
        item.setListUnitPrice(new BigDecimal("8.526315"));
        item.setDiscount(new BigDecimal("0.57"));
        item.setNetUnitPrice(new BigDecimal("4.86"));
        item.setLineAmount(new BigDecimal("194.40"));
        request.setItems(List.of(item));
        return request;
    }
}
