const PURPOSE_OPTIONS = [
  { label: '买家询价', code: 'BUYER_QUOTE' },
  { label: '厂家价格', code: 'SUPPLIER_PRICE' },
  { label: '商品资料', code: 'PRODUCT_IMPORT' },
  { label: '库存盘点', code: 'INVENTORY_COUNT' },
  { label: '采购到货', code: 'PURCHASE_RECEIPT' },
  { label: '其他文件', code: 'UNCLASSIFIED' }
]

const TARGET_OPTIONS = [
  { label: '商品名称', code: 'PRODUCT_NAME' },
  { label: '规格', code: 'SPEC' },
  { label: '单位', code: 'UNIT' },
  { label: '数量', code: 'QUANTITY' },
  { label: '单价', code: 'UNIT_PRICE' },
  { label: '金额', code: 'AMOUNT' },
  { label: '条码', code: 'BARCODE' },
  { label: '厂家货号', code: 'SUPPLIER_SKU' },
  { label: '客户货号', code: 'CUSTOMER_SKU' },
  { label: '备注', code: 'REMARK' },
  { label: '忽略此列', code: 'IGNORE' }
]

const STATUS_META = {
  PARSING: ['正在读取', 'info', 18],
  READY_FOR_MAPPING: ['确认字段', 'warning', 32],
  READY_FOR_REVIEW: ['待复核', 'warning', 72],
  COMMITTED: ['已完成', 'success', 100],
  COMPLETED: ['已完成', 'success', 100],
  FAILED: ['处理失败', 'danger', 100],
  CANCELLED: ['已取消', 'neutral', 100],
  PARTIAL: ['部分完成', 'danger', 100]
}

function purposeLabel(code) {
  const found = PURPOSE_OPTIONS.find((item) => item.code === code)
  return found ? found.label : '其他文件'
}

function formatTime(value) {
  if (!value) return '--'
  const text = String(value).replace('T', ' ')
  return text.length >= 16 ? text.slice(0, 16) : text
}

function statusMeta(status) {
  const meta = STATUS_META[status] || [status || '未知状态', 'neutral', 0]
  return { statusText: meta[0], statusTone: meta[1], progress: meta[2] }
}

function progressText(item) {
  if (item.errorMessage) return item.errorMessage
  if (item.status === 'PARSING') return '正在读取工作表和数据行'
  if (item.status === 'READY_FOR_MAPPING') return `已识别 ${item.sheetCount || 0} 个工作表，请确认字段`
  if (item.status === 'READY_FOR_REVIEW') return `已读取 ${item.rowCount || 0} 行，请复核匹配结果`
  if (item.status === 'COMMITTED' || item.status === 'COMPLETED') return `${item.rowCount || 0} 行已处理完成`
  if (item.status === 'FAILED') return '文件没有处理完成，可以查看原因后重新上传'
  if (item.status === 'CANCELLED') return '任务已由用户取消'
  return '查看任务详情'
}

function normalizeTask(item) {
  const meta = statusMeta(item.status)
  const firstSheet = item.sheets && item.sheets[0]
  return {
    ...item,
    ...meta,
    fileName: item.originalName || item.fileName || '未命名文件',
    purpose: purposeLabel(item.purpose),
    purposeCode: item.purpose,
    sheetName: firstSheet ? firstSheet.sheetName : `${item.sheetCount || 0} 个工作表`,
    createdAt: formatTime(item.createTime),
    updatedAt: formatTime(item.updateTime),
    progressText: progressText(item),
    relationName: '未关联往来单位',
    matched: Number(item.matched || 0),
    pending: Number(item.pending || 0),
    unmatched: Number(item.unmatched || 0),
    conflict: Number(item.conflict || 0),
    additions: Number(item.additions || 0),
    modifications: Number(item.modifications || 0),
    skipped: Number(item.skipped || 0),
    failures: Number(item.failures || 0)
  }
}

function taskRoute(task) {
  if (!task) return '/pages/excel-tasks/index'
  if (task.status === 'READY_FOR_MAPPING') return '/pages/excel-mapping/index'
  if (task.status === 'READY_FOR_REVIEW') return '/pages/excel-review/index'
  return '/pages/excel-task-detail/index'
}

function taskSummary(tasks) {
  return {
    total: tasks.length,
    attention: tasks.filter((item) => ['READY_FOR_MAPPING', 'READY_FOR_REVIEW'].includes(item.status)).length,
    processing: tasks.filter((item) => item.status === 'PARSING').length,
    completed: tasks.filter((item) => ['COMMITTED', 'COMPLETED'].includes(item.status)).length,
    failed: tasks.filter((item) => item.status === 'FAILED').length,
    priceChanges: tasks.filter((item) => item.purposeCode === 'SUPPLIER_PRICE' && item.status !== 'COMMITTED').length,
    unmatched: tasks.reduce((sum, item) => sum + Number(item.unmatched || 0), 0)
  }
}

function guessTarget(name) {
  const value = String(name || '').replace(/\s/g, '').toLowerCase()
  if (/条码|barcode|ean|upc/.test(value)) return 'BARCODE'
  if (/品名|商品名称|产品名称|货品名称|名称/.test(value)) return 'PRODUCT_NAME'
  if (/规格|型号|尺寸/.test(value)) return 'SPEC'
  if (/单位|计量/.test(value)) return 'UNIT'
  if (/数量|需求量|盘点数|库存数|到货数/.test(value)) return 'QUANTITY'
  if (/单价|价格|报价|进价|成本/.test(value)) return 'UNIT_PRICE'
  if (/金额|合计|小计/.test(value)) return 'AMOUNT'
  if (/厂家货号|供应商货号|厂编/.test(value)) return 'SUPPLIER_SKU'
  if (/客户货号|客户编码/.test(value)) return 'CUSTOMER_SKU'
  if (/备注|说明|要求/.test(value)) return 'REMARK'
  return 'IGNORE'
}

function targetLabel(code) {
  const found = TARGET_OPTIONS.find((item) => item.code === code)
  return found ? found.label : '忽略此列'
}

function requiredTarget(code, purpose) {
  if (purpose === 'PRODUCT_IMPORT' && code === 'PRODUCT_NAME') return true
  if (purpose === 'SUPPLIER_PRICE' && code === 'UNIT_PRICE') return true
  if (['INVENTORY_COUNT', 'PURCHASE_RECEIPT'].includes(purpose) && code === 'QUANTITY') return true
  return false
}

function buildMappings(sheet, rowItems, existingMappings, purpose) {
  const existing = new Map((existingMappings || []).map((item) => [Number(item.sourceColumnIndex), item.targetField]))
  return (sheet.columns || []).map((source, sourceColumnIndex) => {
    const code = existing.get(sourceColumnIndex) || guessTarget(source)
    const samples = (rowItems || []).slice(0, 3).map((row) => (row.cells || [])[sourceColumnIndex]).filter(Boolean).join('｜') || '暂无样例'
    return {
      sourceColumnIndex,
      source,
      targetCode: code,
      target: targetLabel(code),
      confidence: existing.has(sourceColumnIndex) ? 100 : (code === 'IGNORE' ? 0 : 88),
      samples,
      required: requiredTarget(code, purpose)
    }
  })
}

function normalizeReviewRow(item) {
  const values = item.normalized || {}
  const candidate = (item.candidates || [])[0]
  const statusMap = {
    MATCHED: ['已匹配', 'success'],
    READY_TO_CREATE: ['可新增', 'warning'],
    NEEDS_REVIEW: ['待确认', 'warning'],
    INVALID: ['数据有误', 'danger'],
    EXCLUDED: ['已跳过', 'neutral']
  }
  const meta = statusMap[item.matchStatus] || [item.matchStatus || '未知', 'neutral']
  return {
    ...item,
    id: item.id,
    sourceRow: item.rowNumber,
    status: item.matchStatus,
    statusText: meta[0],
    tone: meta[1],
    name: values.PRODUCT_NAME || values.BARCODE || '未填写商品名称',
    spec: values.SPEC || '规格未填写',
    quantity: values.QUANTITY || '',
    unit: values.UNIT || '',
    matchName: candidate ? candidate.name : (item.matchedProductId ? `商品 ${item.matchedProductId}` : '尚未选择商品'),
    price: candidate && (candidate.wholesalePrice || candidate.retailPrice || candidate.costPrice),
    basis: item.matchReason || '暂无匹配依据',
    note: (item.issues || []).join('；') || item.matchReason || '请核对本行内容',
    candidates: (item.candidates || []).map((entry) => ({
      ...entry,
      price: entry.wholesalePrice || entry.retailPrice || entry.costPrice || 0,
      specText: [entry.spec, entry.unit].filter(Boolean).join(' · ')
    })),
    beforeText: compactSnapshot(item.before),
    afterText: compactSnapshot(item.after)
  }
}

function compactSnapshot(value) {
  if (!value || !Object.keys(value).length) return '无记录'
  const pairs = Object.keys(value).slice(0, 3).map((key) => `${key}：${value[key] === null ? '-' : value[key]}`)
  return pairs.join('，')
}

function mergeReviewSummaries(summaries) {
  return (summaries || []).reduce((result, item) => {
    if (!item) return result
    result.totalRows += Number(item.totalRows || 0)
    result.matchedRows += Number(item.matchedRows || 0)
    result.readyToCreateRows += Number(item.readyToCreateRows || 0)
    result.needsReviewRows += Number(item.needsReviewRows || 0)
    result.invalidRows += Number(item.invalidRows || 0)
    result.excludedRows += Number(item.excludedRows || 0)
    return result
  }, { totalRows: 0, matchedRows: 0, readyToCreateRows: 0, needsReviewRows: 0, invalidRows: 0, excludedRows: 0 })
}

module.exports = {
  PURPOSE_OPTIONS,
  TARGET_OPTIONS,
  purposeLabel,
  targetLabel,
  normalizeTask,
  taskRoute,
  taskSummary,
  buildMappings,
  normalizeReviewRow,
  mergeReviewSummaries,
  formatTime
}
