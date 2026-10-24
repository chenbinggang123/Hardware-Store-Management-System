package com.example.demo.billing;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class HandwrittenItemContinuationTest {
    @Test void continuesAcrossManyOcrDiameterAndNumericRowsUntilNewProduct() {
        var c = new BillingContent();
        var first = line("镀钛钻头", "Φ1");
        var a = line("P5.5", ""); var b = line("中5、8", "");
        var d = line("无法识别：8.5", ""); var e = line("？巾10", "");
        var f = line("未识别", "12");
        var newProduct = line("合金铰刀巾16", ""); var next = line("巾20", "");
        a.setQuantity(new BigDecimal("100")); a.setPrice(new BigDecimal("3.9")); a.setUnit("支");
        c.setLines(List.of(first, a, b, d, e, f, newProduct, next));
        HandwrittenItemContinuation.repair(c);
        for (var row : List.of(a, b, d, e, f)) {
            assertThat(row.getName()).isEqualTo("镀钛钻头");
            assertThat(row.getRemark()).contains("名称沿用第1行", "待确认");
            assertThat(row.isRemember()).isFalse();
        }
        assertThat(b.getSpecification()).isEqualTo("中5、8");
        assertThat(d.getSpecification()).isEqualTo("8.5");
        assertThat(d.getRemark()).contains("无法识别：8.5");
        assertThat(a.getQuantity()).isEqualByComparingTo("100");
        assertThat(a.getPrice()).isEqualByComparingTo("3.9");
        assertThat(b.getQuantity()).isNull(); assertThat(b.getUnit()).isNull();
        assertThat(next.getName()).isEqualTo("合金铰刀");
        assertThat(next.getRemark()).contains("第7行");
        String evidence = b.getRemark(); HandwrittenItemContinuation.repair(c);
        assertThat(b.getRemark()).isEqualTo(evidence);
    }

    @Test void unknownWithoutNumbersBreaksChainRatherThanCopyingValues() {
        var c = new BillingContent(); var first = line("钻头", "Φ1");
        var unknown = line("未识别", ""); var next = line("P5.5", "");
        c.setLines(List.of(first, unknown, next)); HandwrittenItemContinuation.repair(c);
        assertThat(next.getName()).isEmpty();
        assertThat(next.getSpecification()).isEqualTo("P5.5");
    }
    private BillingContent.Line line(String name,String spec) {
        var line=new BillingContent.Line();line.setName(name);line.setSpecification(spec);return line;
    }
    @Test void restoresAdjacentSpecificationsButDoesNotCopyPricesOrUnits() {
        var first=line("排气销","M6");first.setPrice(BigDecimal.TEN);first.setUnit("个");
        var next=line("M10","");next.setQuantity(new BigDecimal("5"));next.setRemember(true);
        var drill=line("麻花钻3×13","");var shortDrill=line("3×8","");
        var c=new BillingContent();c.setLines(List.of(first,next,drill,shortDrill));
        HandwrittenItemContinuation.repair(c);
        assertThat(next.getName()).isEqualTo("排气销");assertThat(next.getSpecification()).isEqualTo("M10");
        assertThat(next.getPrice()).isNull();assertThat(next.getUnit()).isNull();assertThat(next.getQuantity()).isEqualByComparingTo("5");
        assertThat(next.getReferenceCaution()).contains("名称沿用第1行","待确认","M10");assertThat(next.isRemember()).isFalse();
        assertThat(drill.getName()).isEqualTo("麻花钻");assertThat(drill.getSpecification()).isEqualTo("3×13");
        assertThat(shortDrill.getName()).isEqualTo("麻花钻");assertThat(shortDrill.getSpecification()).isEqualTo("3×8");
    }
    @Test void respectsExplicitNamesEmptyRowsAndFirstRowWithoutAnAnchor() {
        var c=new BillingContent();var first=line("Φ5.5","");var named=line("铰刀","Φ16");
        var other=line("轴12","");var blank=line("","");var orphan=line("", "Φ20");
        c.setLines(List.of(first,named,other,blank,orphan));HandwrittenItemContinuation.repair(c);
        assertThat(first.getName()).isEmpty();assertThat(first.getSpecification()).isEqualTo("Φ5.5");
        assertThat(other.getName()).isEqualTo("轴12");assertThat(orphan.getName()).isEmpty();
    }
    @Test void chainedRowsKeepTheOriginalAnchorAndNeverOverrideConflictingSpecifications() {
        var c=new BillingContent();var a=line("合金铰刀","Φ16");var b=line("Φ20","");var d=line("Φ8.5","");var conflict=line("M10","M12");
        c.setLines(List.of(a,b,d,conflict));HandwrittenItemContinuation.repair(c);
        assertThat(d.getName()).isEqualTo("合金铰刀");assertThat(d.getRemark()).contains("第1行");
        assertThat(conflict.getName()).isEqualTo("M10");assertThat(conflict.getSpecification()).isEqualTo("M12");
        String remark=b.getRemark();HandwrittenItemContinuation.repair(c);assertThat(b.getRemark()).isEqualTo(remark);
    }
}
