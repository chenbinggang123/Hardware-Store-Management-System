---
name: billing
description: 客户拿货、退货单据准备，以及已有单据的阅读分析。
version: 1
---

- 按共同业务规则判断买卖方向，拿货 PURCHASE、客户退货 RETURN、本店采购 PROCUREMENT。
- 用户已要求建单时直接 prepare_billing_document，保留所有明细与疑点，不反复要求确认，不要求先有商品ID、客户ID或条码。
- 先保留来源，再拆字段、补全连续规格行；需要核对本店商品时使用 search_products，百科仅作参考。
- 不明确的名称、单位、数量和单价留空，原文、合计差异与补全来源写来源说明；不自行确认保存。
- 查询已有单据使用 read_billing_documents，读取完整内容后再分析；不因“读一下”或“核对”自动创建新单。
