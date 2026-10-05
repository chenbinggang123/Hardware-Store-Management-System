package com.example.demo.excel;

import com.example.demo.excel.dto.ExcelComparisonRequest;
import com.example.demo.excel.entity.ExcelComparison;
import com.example.demo.excel.entity.ExcelComparisonItem;
import com.example.demo.excel.entity.ExcelTask;
import com.example.demo.excel.entity.ExcelTaskRowResult;
import com.example.demo.excel.repository.ExcelComparisonItemRepository;
import com.example.demo.excel.repository.ExcelComparisonRepository;
import com.example.demo.excel.repository.ExcelTaskRepository;
import com.example.demo.excel.repository.ExcelTaskRowResultRepository;
import com.example.demo.excel.service.ExcelComparisonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExcelComparisonServiceTest {
    @Test
    void classifiesChangedAndAddedRowsUsingStableMatchKey() {
        ExcelTaskRepository taskRepository = mock(ExcelTaskRepository.class);
        ExcelTaskRowResultRepository rowRepository = mock(ExcelTaskRowResultRepository.class);
        ExcelComparisonRepository comparisonRepository = mock(ExcelComparisonRepository.class);
        ExcelComparisonItemRepository itemRepository = mock(ExcelComparisonItemRepository.class);
        ExcelTask base = task(1L, 5L, 2L);
        ExcelTask newer = task(2L, 5L, 4L);
        AtomicReference<ExcelComparison> saved = new AtomicReference<>();
        when(taskRepository.findByIdAndOperatorId(1L, 5L)).thenReturn(Optional.of(base));
        when(taskRepository.findByIdAndOperatorId(2L, 5L)).thenReturn(Optional.of(newer));
        when(comparisonRepository.findByOperatorIdAndIdempotencyKey(eq(5L), anyString()))
                .thenReturn(Optional.empty());
        when(comparisonRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ExcelComparison value = invocation.getArgument(0);
            value.setId(90L);
            saved.set(value);
            return value;
        });
        when(comparisonRepository.findById(90L)).thenAnswer(ignored -> Optional.of(saved.get()));
        when(comparisonRepository.save(any())).thenAnswer(invocation -> {
            ExcelComparison value = invocation.getArgument(0);
            saved.set(value);
            return value;
        });
        when(rowRepository.findByTaskIdOrderBySheetIdAscRowNumberAsc(1L)).thenReturn(List.of(
                row(11L, 1L, 2, "A001", "10.00")));
        when(rowRepository.findByTaskIdOrderBySheetIdAscRowNumberAsc(2L)).thenReturn(List.of(
                row(21L, 2L, 2, "A001", "12.00"), row(22L, 2L, 3, "B002", "5.00")));
        when(itemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ExcelComparisonService service = new ExcelComparisonService(taskRepository, rowRepository,
                comparisonRepository, itemRepository, new ObjectMapper(), Runnable::run);

        var result = service.create(new ExcelComparisonRequest(1L, 2L, 2L, 4L,
                List.of("BARCODE"), List.of("UNIT_PRICE"), "compare-key-001"), 5L);

        assertEquals("COMPLETED", result.status());
        assertEquals(1, ((Number) result.summary().get("changed")).intValue());
        assertEquals(1, ((Number) result.summary().get("added")).intValue());
        ArgumentCaptor<List<ExcelComparisonItem>> items = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(items.capture());
        assertEquals(List.of("CHANGED", "ADDED"),
                items.getValue().stream().map(ExcelComparisonItem::getChangeType).toList());
    }

    private ExcelTask task(Long id, Long operatorId, Long version) {
        ExcelTask value = new ExcelTask();
        value.setId(id);
        value.setOperatorId(operatorId);
        value.setPurpose("SUPPLIER_PRICE");
        value.setStatus("READY_FOR_REVIEW");
        value.setVersion(version);
        return value;
    }

    private ExcelTaskRowResult row(Long id, Long taskId, int number, String barcode, String price) {
        ExcelTaskRowResult value = new ExcelTaskRowResult();
        value.setId(id);
        value.setTaskId(taskId);
        value.setSheetId(1L);
        value.setRowNumber(number);
        value.setMatchStatus("MATCHED");
        value.setMatchedProductId(id);
        value.setNormalizedJson("{\"PRODUCT_NAME\":\"商品" + barcode + "\",\"BARCODE\":\""
                + barcode + "\",\"UNIT_PRICE\":\"" + price + "\"}");
        return value;
    }
}
