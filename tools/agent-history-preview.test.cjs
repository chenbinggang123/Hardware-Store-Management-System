const { test } = require('node:test')
const assert = require('node:assert/strict')
const { formatConversationPreview } = require('../miniprogram/utils/agent-format')

test('history converts full and old truncated billing results into plain language', () => {
  for (const value of [
    '操作已成功执行。结果：{"kind":"BILLING_DOCUMENT","documentId":123}',
    '操作已成功执行。结果：{"kind":"BILLING_DOCUM…'
  ]) assert.equal(formatConversationPreview(value), '单据草稿已建立，待在编辑页核对确认。')
})

test('history strips markdown but keeps product specifications and amounts', () => {
  assert.equal(formatConversationPreview('**结论** 无法直接录入：`M6×20`，单价 ¥3.50。'), '结论 无法直接录入：M6×20，单价 ¥3.50。')
  assert.equal(formatConversationPreview('## 结果\n- [查看商品](https://example.com)\n**库存** 20 支'), '结果 查看商品 库存 20 支')
})

test('history handles generic, malformed and empty results', () => {
  assert.equal(formatConversationPreview('{"message":"**商品已更新**","productId":123}'), '商品已更新')
  for (const value of ['{"id":123}', '操作已成功执行。结果：{"kind":', '```json\n{"id":123}\n```']) {
    assert.equal(formatConversationPreview(value), '请打开对话查看处理结果。')
  }
  assert.equal(formatConversationPreview(null), '暂无消息')
})

test('history drawer applies formatting before searching and displaying old records', async () => {
  const fs = require('node:fs'), vm = require('node:vm')
  let page
  vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/pages/agent/index.js'), 'utf8'), {
    Page(value) { page = value }, wx: {}, require(key) {
      if (key === '../../utils/api') return { getAgentConversations: async () => [
        { id: 1, title: '保存采购单', preview: '操作已成功执行。结果：{"kind":"BILLING_DOCUM…' },
        { id: 2, title: '新增商品', preview: '**结论** 无法直接录入' }
      ] }
      if (key === '../../utils/agent-format') return require('../miniprogram/utils/agent-format')
      return {}
    }, setTimeout, clearTimeout
  })
  page.setData = function (value) { Object.assign(this.data, value) }
  await page.loadConversations()
  assert.equal(page.data.allConversations[0].preview, '单据草稿已建立，待在编辑页核对确认。')
  assert.equal(page.data.allConversations[1].preview, '结论 无法直接录入')
})
