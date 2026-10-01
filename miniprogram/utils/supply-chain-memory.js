const MAPPING_KEY = 'supply_chain_product_mappings_v1'
const PRICE_SYNC_KEY = 'supply_chain_price_sync_v1'

const mappingSeed = [
  { id: 1, productId: 2, productName: '冲击电钻', productSpec: '680W', sourceType: '厂家货号', sourceName: 'JSDZ-680', sourceSpec: '13mm 680瓦', relationName: '江苏机电设备厂', lastUsed: '今天 09:28', useCount: 18, status: 'ACTIVE' },
  { id: 2, productId: 2, productName: '冲击电钻', productSpec: '680W', sourceType: '买家叫法', sourceName: '手提冲击钻', sourceSpec: '680W 标配', relationName: '城建安装公司', lastUsed: '今天 09:42', useCount: 7, status: 'ACTIVE' },
  { id: 3, productId: 2, productName: '冲击电钻', productSpec: '680W', sourceType: '买家叫法', sourceName: '电锤小号', sourceSpec: '两用 680W', relationName: '老王五金工程队', lastUsed: '9月29日', useCount: 11, status: 'ACTIVE' },
  { id: 4, productId: 1, productName: '羊角锤', productSpec: '16oz', sourceType: '厂家货号', sourceName: 'NB-H16', sourceSpec: '16安士木柄', relationName: '宁波五金厂', lastUsed: '9月27日', useCount: 26, status: 'ACTIVE' },
  { id: 5, productId: 1, productName: '羊角锤', productSpec: '16oz', sourceType: '买家叫法', sourceName: '羊角榔头', sourceSpec: '16安士', relationName: '城建安装公司', lastUsed: '今天 09:42', useCount: 4, status: 'ACTIVE' },
  { id: 6, productId: 3, productName: 'PVC水管', productSpec: '20mm', sourceType: '厂家货号', sourceName: 'FS-PVC-DN20', sourceSpec: '4米 DN20', relationName: '佛山建材厂', lastUsed: '9月25日', useCount: 31, status: 'ACTIVE' }
]

const priceHistory = {
  1: [
    { id: 1, date: '2026-09-27', type: '厂家成本', before: 18.2, after: 18.8, change: 3.3, source: '宁波五金厂价格表', operator: '管理员' },
    { id: 2, date: '2026-08-15', type: '批发价', before: 23.5, after: 24.5, change: 4.3, source: '人工调整', operator: '管理员' },
    { id: 3, date: '2026-07-02', type: '老客户价', before: 22.5, after: 23, change: 2.2, source: '价格策略调整', operator: '管理员' }
  ],
  2: [
    { id: 1, date: '2026-09-30', type: '厂家成本', before: 188, after: 198, change: 5.3, source: '秋季电动工具价格表.xlsx', operator: '待确认' },
    { id: 2, date: '2026-09-01', type: '批发价', before: 238, after: 245, change: 2.9, source: '月度价格策略', operator: '管理员' },
    { id: 3, date: '2026-08-18', type: '老客户价', before: 235, after: 238, change: 1.3, source: '客户价格复核', operator: '管理员' },
    { id: 4, date: '2026-07-10', type: '厂家成本', before: 182, after: 188, change: 3.3, source: '江苏机电设备厂报价', operator: '管理员' }
  ],
  3: [
    { id: 1, date: '2026-09-25', type: '厂家成本', before: 9.2, after: 9.6, change: 4.3, source: '佛山管件价格表', operator: '管理员' },
    { id: 2, date: '2026-08-02', type: '批发价', before: 13, after: 13.5, change: 3.8, source: '人工调整', operator: '管理员' }
  ]
}

const priceSyncSeed = [
  { id: 1, sourceRow: 2, factoryCode: 'JSDZ-680', name: '冲击电钻', spec: '680W', oldCost: 198, newCost: 208, change: 5.1, match: '冲击电钻 680W', tone: 'normal', selected: true },
  { id: 2, sourceRow: 3, factoryCode: 'JSQG-125', name: '角磨机', spec: '125mm', oldCost: 142, newCost: 169, change: 19.0, match: '角向磨光机 125mm', tone: 'warning', selected: false },
  { id: 3, sourceRow: 4, factoryCode: 'JSQP-24', name: '气泵', spec: '24L', oldCost: 368, newCost: 355, change: -3.5, match: '静音气泵 24L', tone: 'normal', selected: true },
  { id: 4, sourceRow: 5, factoryCode: 'JSDJ-12', name: '充电电钻', spec: '12V 双电', oldCost: 176, newCost: 246, change: 39.8, match: '充电钻 12V', tone: 'danger', selected: false },
  { id: 5, sourceRow: 6, factoryCode: 'JSHJ-200', name: '电焊机', spec: 'ZX7-200', oldCost: 485, newCost: 499, change: 2.9, match: '逆变电焊机 200A', tone: 'normal', selected: true },
  { id: 6, sourceRow: 7, factoryCode: 'JSWD-02', name: '无刷电钻', spec: '20V', oldCost: 0, newCost: 328, change: 0, match: '尚未匹配内部商品', tone: 'warning', selected: false }
]

const versionComparison = {
  taskId: 'EX-20260929-008',
  base: { version: 'V1', fileName: '水电材料需求.xlsx', uploadedAt: '9月29日 14:58', rows: 43 },
  current: { version: 'V2', fileName: '水电材料需求第二版.xlsx', uploadedAt: '9月29日 15:18', rows: 46 },
  summary: { added: 3, removed: 0, quantityChanged: 4, noteChanged: 2, unchanged: 37 },
  rows: [
    { id: 1, sourceRow: 8, name: 'PVC水管', spec: '20mm', field: '数量', before: '120 根', after: '200 根', tone: 'changed' },
    { id: 2, sourceRow: 13, name: '铜芯电线', spec: '2.5mm²', field: '数量', before: '8 卷', after: '12 卷', tone: 'changed' },
    { id: 3, sourceRow: 21, name: '防水胶布', spec: '黑色', field: '新增行', before: '无', after: '30 卷', tone: 'added' },
    { id: 4, sourceRow: 32, name: '空气开关', spec: '2P 32A', field: '备注', before: '德力西', after: '德力西或正泰', tone: 'changed' },
    { id: 5, sourceRow: 44, name: '生料带', spec: '20m', field: '新增行', before: '无', after: '50 卷', tone: 'added' }
  ]
}

function clone(value) { return JSON.parse(JSON.stringify(value)) }
function read(key, fallback) {
  const value = wx.getStorageSync(key)
  if (Array.isArray(value) && value.length) return clone(value)
  wx.setStorageSync(key, fallback)
  return clone(fallback)
}

function getMappings(productId) { return read(MAPPING_KEY, mappingSeed).filter((item) => !productId || Number(item.productId) === Number(productId)) }
function revokeMapping(id) {
  const mappings = read(MAPPING_KEY, mappingSeed).map((item) => Number(item.id) === Number(id) ? { ...item, status: 'REVOKED' } : item)
  wx.setStorageSync(MAPPING_KEY, mappings)
  return clone(mappings.find((item) => Number(item.id) === Number(id)))
}
function getPriceHistory(productId) { return clone(priceHistory[Number(productId)] || priceHistory[2]) }
function getPriceSyncRows() { return read(PRICE_SYNC_KEY, priceSyncSeed) }
function savePriceSyncRows(rows) { wx.setStorageSync(PRICE_SYNC_KEY, rows); return clone(rows) }
function getVersionComparison() { return clone(versionComparison) }

module.exports = { getMappings, revokeMapping, getPriceHistory, getPriceSyncRows, savePriceSyncRows, getVersionComparison }
