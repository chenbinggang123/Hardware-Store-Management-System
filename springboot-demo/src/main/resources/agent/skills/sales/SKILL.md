---
name: sales
description: 历史能力名；客户拿货、退货现统一走单据编辑页。
version: 2
---

- 客户拿货用 PURCHASE、客户退货用 RETURN，按共同业务规则通过 prepare_billing_document 建立编辑页草稿；不要求先提供客户ID、商品ID或补齐所有字段。
- 用户在编辑页核对并确认保存；不使用旧销售草稿和旧正式销售单提交流程。
- 读取已有单据用 read_billing_documents；应收及付款用客户账本工具，不从票面金额推断。
