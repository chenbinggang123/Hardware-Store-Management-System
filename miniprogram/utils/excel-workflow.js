const STORAGE_KEY = 'excel_workflow_tasks_v1'

const seedTasks = [
  {
    id: 'EX-20260930-004',
    purpose: '买家询价',
    relationType: 'ToB 买家',
    relationName: '城建安装公司',
    fileName: '城建项目五金询价单.xlsx',
    sheetName: '询价清单',
    rowCount: 102,
    status: 'REVIEW',
    statusText: '待确认',
    statusTone: 'warning',
    progress: 76,
    progressText: '已匹配 78 行，24 行需要处理',
    updatedAt: '今天 09:42',
    createdAt: '2026-09-30 09:16',
    matched: 78,
    pending: 15,
    unmatched: 7,
    conflict: 2,
    additions: 3,
    modifications: 72,
    skipped: 25,
    failures: 2,
    source: '微信会话',
    outputName: '',
    mappings: [
      { source: '品名', target: '商品名称', confidence: 98, samples: '冲击钻｜羊角锤｜PVC 管', required: true },
      { source: '型号规格', target: '规格', confidence: 96, samples: '680W｜16oz｜20mm', required: true },
      { source: '单位', target: '单位', confidence: 94, samples: '台｜把｜根', required: true },
      { source: '需求数量', target: '数量', confidence: 91, samples: '12｜50｜200', required: false },
      { source: '备注', target: '备注', confidence: 87, samples: '含税｜国标｜送货到场', required: false }
    ],
    rows: [
      { id: 1, sourceRow: 2, name: '冲击钻', spec: '680W', quantity: 12, unit: '台', status: 'MATCHED', statusText: '已匹配', tone: 'success', matchName: '冲击电钻 680W', price: 245, basis: '最近成交价', note: '唯一匹配内部商品' },
      { id: 2, sourceRow: 3, name: '羊角榔头', spec: '16安士', quantity: 50, unit: '把', status: 'PENDING', statusText: '待确认', tone: 'warning', matchName: '羊角锤 16oz', price: 24.5, basis: '批发价', note: '名称相似度 87%，请确认' },
      { id: 3, sourceRow: 4, name: 'PPR 直管', spec: 'DN20', quantity: 200, unit: '根', status: 'UNMATCHED', statusText: '未匹配', tone: 'warning', matchName: '尚未选择商品', price: 0, basis: '无价格依据', note: '可以新建商品或暂时跳过' },
      { id: 4, sourceRow: 5, name: '电钻头套装', spec: '13件', quantity: 6, unit: '套', status: 'CONFLICT', statusText: '冲突', tone: 'danger', matchName: '电钻配件套装', price: 88, basis: '客户历史价', note: '条码与另一个商品重复' },
      { id: 5, sourceRow: 6, name: 'PVC水管', spec: '20mm', quantity: 36, unit: '根', status: 'MATCHED', statusText: '已匹配', tone: 'success', matchName: 'PVC水管 20mm', price: 13.5, basis: '批发价', note: '字段完整' }
    ],
    logs: [
      { time: '今天 09:42', title: '完成自动匹配', detail: '78 行已匹配，24 行需要人工处理' },
      { time: '今天 09:18', title: '识别字段', detail: '应用城建安装公司询价模板' },
      { time: '今天 09:16', title: '上传文件', detail: '操作人：管理员' }
    ]
  },
  {
    id: 'EX-20260930-003', purpose: '厂家价格', relationType: '源头厂商', relationName: '江苏机电设备厂',
    fileName: '秋季电动工具价格表.xlsx', sheetName: '最新报价', rowCount: 286,
    status: 'PROCESSING', statusText: '处理中', statusTone: 'info', progress: 64,
    progressText: '正在匹配第 184 / 286 行', updatedAt: '今天 09:28', createdAt: '2026-09-30 09:05',
    matched: 184, pending: 0, unmatched: 0, conflict: 0, additions: 0, modifications: 0, skipped: 0, failures: 0,
    source: '文件上传', outputName: '', mappings: [], rows: [], logs: [
      { time: '今天 09:28', title: '正在匹配商品', detail: '已处理 184 / 286 行' },
      { time: '今天 09:05', title: '上传文件', detail: '来源：江苏机电设备厂' }
    ]
  },
  {
    id: 'EX-20260929-011', purpose: '库存盘点', relationType: '内部经营', relationName: '一号仓',
    fileName: '九月末仓库盘点.xlsx', sheetName: '一号仓', rowCount: 168,
    status: 'CONFLICT', statusText: '存在冲突', statusTone: 'danger', progress: 100,
    progressText: '4 行库存变化需要重新核对', updatedAt: '昨天 18:21', createdAt: '2026-09-29 17:40',
    matched: 164, pending: 0, unmatched: 0, conflict: 4, additions: 0, modifications: 164, skipped: 0, failures: 4,
    source: '文件上传', outputName: '', mappings: [], rows: [], logs: [
      { time: '昨天 18:21', title: '校验未通过', detail: '4 行账面库存已发生变化' },
      { time: '昨天 17:40', title: '上传文件', detail: '操作人：管理员' }
    ]
  },
  {
    id: 'EX-20260929-010', purpose: '商品资料', relationType: '源头厂商', relationName: '佛山建材厂',
    fileName: '管件商品资料更新.csv', sheetName: '商品资料', rowCount: 94,
    status: 'PARTIAL', statusText: '部分完成', statusTone: 'danger', progress: 100,
    progressText: '成功 88 行，6 行写入失败，可单独重试', updatedAt: '昨天 16:08', createdAt: '2026-09-29 15:34',
    matched: 88, pending: 0, unmatched: 0, conflict: 0, additions: 21, modifications: 67, skipped: 0, failures: 6,
    source: '文件上传', outputName: '管件商品资料更新_部分完成.xlsx', mappings: [], rows: [], logs: [
      { time: '昨天 16:08', title: '部分写入成功', detail: '成功 88 行，失败 6 行，成功结果已保留' },
      { time: '昨天 15:34', title: '上传文件', detail: '来源：佛山建材厂' }
    ]
  },
  {
    id: 'EX-20260929-008', purpose: '买家报价', relationType: 'ToB 买家', relationName: '老王五金工程队',
    fileName: '水电材料需求第二版.xlsx', sheetName: '材料清单', rowCount: 46,
    status: 'COMPLETED', statusText: '已完成', statusTone: 'success', progress: 100,
    progressText: '46 行已处理，报价文件已生成', updatedAt: '昨天 15:32', createdAt: '2026-09-29 14:58',
    matched: 43, pending: 0, unmatched: 0, conflict: 0, additions: 0, modifications: 43, skipped: 3, failures: 0,
    source: '小五会话', outputName: '水电材料需求第二版_已报价.xlsx', mappings: [], rows: [], logs: [
      { time: '昨天 15:32', title: '生成报价文件', detail: '43 行已回填单价与金额' },
      { time: '昨天 15:31', title: '写入完成', detail: '成功 43 行，跳过 3 行' },
      { time: '昨天 14:58', title: '上传文件', detail: '来源：小五会话' }
    ]
  }
]

function clone(value) {
  return JSON.parse(JSON.stringify(value))
}

function readTasks() {
  const saved = wx.getStorageSync(STORAGE_KEY)
  if (Array.isArray(saved) && saved.length) return clone(saved)
  wx.setStorageSync(STORAGE_KEY, seedTasks)
  return clone(seedTasks)
}

function writeTasks(tasks) {
  wx.setStorageSync(STORAGE_KEY, tasks)
  return clone(tasks)
}

function getTasks() {
  return readTasks()
}

function getTask(id) {
  return readTasks().find((item) => String(item.id) === String(id)) || null
}

function getSummary() {
  const tasks = readTasks()
  return {
    total: tasks.length,
    attention: tasks.filter((item) => ['REVIEW', 'CONFLICT', 'MAPPING'].includes(item.status)).length,
    processing: tasks.filter((item) => item.status === 'PROCESSING').length,
    completed: tasks.filter((item) => item.status === 'COMPLETED').length,
    priceChanges: tasks.filter((item) => item.purpose === '厂家价格' && item.status !== 'COMPLETED').length,
    unmatched: tasks.reduce((sum, item) => sum + Number(item.unmatched || 0), 0)
  }
}

function createTask(payload) {
  const tasks = readTasks()
  const suffix = String(Date.now()).slice(-5)
  const task = {
    id: `EX-20260930-${suffix}`,
    purpose: payload.purpose || '其他文件', relationType: payload.relationType || '暂不关联', relationName: payload.relationName || '暂不关联',
    fileName: payload.fileName || '待处理文件.xlsx', sheetName: 'Sheet1', rowCount: 36,
    status: 'MAPPING', statusText: '确认字段', statusTone: 'warning', progress: 28,
    progressText: '已识别 5 个字段，请确认映射', updatedAt: '刚刚', createdAt: '2026-09-30 10:00',
    matched: 0, pending: 0, unmatched: 0, conflict: 0, additions: 0, modifications: 0, skipped: 0, failures: 0,
    source: payload.source || '文件上传', outputName: '',
    mappings: clone(seedTasks[0].mappings), rows: clone(seedTasks[0].rows),
    logs: [{ time: '刚刚', title: '识别字段', detail: '已识别 5 个字段，等待确认' }, { time: '刚刚', title: '上传文件', detail: `关联对象：${payload.relationName || '暂不关联'}` }]
  }
  tasks.unshift(task)
  writeTasks(tasks)
  return clone(task)
}

function updateTask(id, patch) {
  const tasks = readTasks()
  const index = tasks.findIndex((item) => String(item.id) === String(id))
  if (index < 0) return null
  tasks[index] = { ...tasks[index], ...patch, updatedAt: '刚刚' }
  writeTasks(tasks)
  return clone(tasks[index])
}

function updateMappings(id, mappings) {
  return updateTask(id, {
    mappings: clone(mappings), status: 'REVIEW', statusText: '待确认', statusTone: 'warning', progress: 76,
    progressText: '已匹配 78 行，24 行需要处理', matched: 78, pending: 15, unmatched: 7, conflict: 2,
    logs: [{ time: '刚刚', title: '完成自动匹配', detail: '78 行已匹配，24 行需要人工处理' }].concat((getTask(id) || {}).logs || [])
  })
}

function completeTask(id) {
  const task = getTask(id)
  if (!task) return null
  return updateTask(id, {
    status: 'COMPLETED', statusText: '已完成', statusTone: 'success', progress: 100,
    progressText: `${task.rowCount} 行已处理，结果文件已生成`, failures: 0,
    outputName: `${task.fileName.replace(/\.(xlsx|xls|csv)$/i, '')}_处理结果.xlsx`,
    logs: [{ time: '刚刚', title: '写入并生成结果', detail: `成功 ${task.modifications + task.additions} 行，跳过 ${task.skipped} 行` }].concat(task.logs || [])
  })
}

module.exports = { getTasks, getTask, getSummary, createTask, updateTask, updateMappings, completeTask }
