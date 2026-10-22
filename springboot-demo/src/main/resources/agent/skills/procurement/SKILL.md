---
name: procurement
description: 通过统一单据编辑页处理源头厂商采购进货，或查询厂商进货账本。
version: 3
---

- 采购单是本店向源头厂商买货。供应商票面即使写“销售清单”“送货单”，仍按本店采购处理。
- 新采购单使用 prepare_billing_document，documentType 为 PROCUREMENT；customerName 为源头厂商，companyName 为本店买方名称，sourceDocumentNumber 为供应商原单号。
- 只创建可编辑草稿。用户在编辑页核对并正式保存后，系统自动记录商品、更新进价并增加采购数量。同厂商按名称、规格、单位匹配商品；不同厂商保留各自进价。
- 查询采购记录使用 read_billing_documents，按 factory 或 keyword 搜索，再按 documentId 读取完整明细。厂商账本与商品总页读取同一套单据与商品资料。
- 数量和单位分开填写；不清楚的名称、数量、日期留空并写 sourceNote。连续行的省略符只在前行明确时继承名称。票面手写加项、折让、实付与合计差异原样保留，不得倒推修改数量或单价。
- 不再用 record_source_purchase_order 建立另一套新采购记录；旧凭证仅作为历史来源。
