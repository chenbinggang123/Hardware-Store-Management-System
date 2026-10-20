function cleanMarkdown(value) {
  return String(value || '')
    .replace(/\r/g, '')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/__([^_]+)__/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/^#{1,6}\s*/gm, '')
    .trim()
}

function plainBlocks(value) {
  const text = String(value || '').replace(/\r/g, '')
    .replace(/\|\s*:?-{3,}:?\s*(?=\||$)/g, '')
    .replace(/\s+-\s+(?=[^：:\n]{1,18}[：:])/g, '\n')
    // Only trim spaces around table separators. Using \s here also consumes
    // newlines and collapses every OCR order row into one unreadable paragraph.
    .replace(/[ \t]*\|[ \t]*/g, ' · ')

  return text.split(/\n+/).map((line) => line.trim()).filter(Boolean).map((source) => {
    if (/^(?:-{2,}|_{2,}|\*{2,}|[·•。；;，,\s]+)$/.test(source)) return null
    const heading = source.match(/^#{1,6}\s+(.+)$/)
    if (heading) return { type: 'heading', text: cleanMarkdown(heading[1]) }
    const list = source.match(/^\s*(?:[-+*·•●▪]|(\d+)[.)、])\s*(.+)$/)
    if (list) return { type: 'list', marker: list[1] ? `${list[1]}.` : '•', text: cleanMarkdown(list[2]) }
    const line = cleanMarkdown(source)
    if (/^(?:结果|重点|明细|商品明细|待确认|下一步|文件在哪看|处理结果|查询结果|合计|建议)$/.test(line)) {
      return { type: 'heading', text: line }
    }
    const field = line.match(/^-?\s*([^：:]{1,18})[：:]\s*(.+)$/)
    if (field) return { type: 'field', label: field[1].trim(), value: field[2].trim() }
    return { type: 'text', text: line.replace(/^[-·•●▪]\s*/, '') }
  }).filter(Boolean)
}

function tableCells(line) {
  const source = String(line || '').trim()
  if (!source.includes('|')) return []
  return source.replace(/^\|/, '').replace(/\|$/, '').split('|').map((cell) => cleanMarkdown(cell))
}

function isSeparatorCells(cells) {
  return cells.length > 0 && cells.every((cell) => /^:?-{3,}:?$/.test(cell.replace(/\s/g, '')))
}

function sourceOrderTableBlocks(value) {
  const lines = String(value || '').replace(/\r/g, '').split('\n')
  for (let headerLine = 0; headerLine < lines.length; headerLine += 1) {
    const headers = tableCells(lines[headerLine])
    const normalized = headers.map((header) => header.replace(/\s/g, ''))
    const lineIndex = normalized.findIndex((header) => header === '行号')
    const nameIndex = normalized.findIndex((header) => header === '商品名称' || header === '商品')
    const unitIndex = normalized.findIndex((header) => header === '单位')
    const quantityIndex = normalized.findIndex((header) => header === '数量')
    const priceIndex = normalized.findIndex((header) => header === '单价')
    const amountIndex = normalized.findIndex((header) => header === '行金额' || header === '金额')
    if ([lineIndex, nameIndex, unitIndex, quantityIndex, priceIndex, amountIndex].some((index) => index < 0)) continue

    const rows = []
    let cursor = headerLine + 1
    while (cursor < lines.length) {
      const cells = tableCells(lines[cursor])
      if (!cells.length) break
      if (isSeparatorCells(cells)) {
        cursor += 1
        continue
      }
      const lineNo = cells[lineIndex]
      if (!/^\d+$/.test(lineNo || '')) break
      rows.push({
        type: 'field',
        label: `第${lineNo}行`,
        value: `${cells[nameIndex] || '未识别商品'} · ${cells[unitIndex] || '单位待核对'} · ${cells[quantityIndex] || '?'} × ¥${cells[priceIndex] || '?'} = ¥${cells[amountIndex] || '?'}`
      })
      cursor += 1
    }
    if (!rows.length) continue
    return [
      ...plainBlocks(lines.slice(0, headerLine).join('\n')),
      ...rows,
      ...plainBlocks(lines.slice(cursor).join('\n'))
    ]
  }
  return null
}

function productTableBlocks(value) {
  if (!String(value || '').includes('|')) return null
  const tokens = String(value).split('|').map((token) => cleanMarkdown(token)).filter((token) => {
    return token && !/^:?-{3,}:?$/.test(token)
  })
  const headerIndex = tokens.findIndex((token) => token.replace(/\s/g, '').toLowerCase() === '商品id')
  if (headerIndex < 0) return null

  const headers = tokens.slice(headerIndex, headerIndex + 5).map((token) => token.replace(/\s/g, ''))
  if (headers.join(',') !== '商品ID,名称,规格,单位,零售价') return null

  const products = []
  let cursor = headerIndex + 5
  while (cursor + 4 < tokens.length && /^\d+$/.test(tokens[cursor])) {
    products.push({
      type: 'product', id: tokens[cursor], name: tokens[cursor + 1], spec: tokens[cursor + 2],
      unit: tokens[cursor + 3], price: tokens[cursor + 4].replace(/^¥\s*/, '')
    })
    cursor += 5
  }
  if (!products.length) return null

  return [
    ...plainBlocks(tokens.slice(0, headerIndex).join(' ')),
    ...products,
    ...plainBlocks(tokens.slice(cursor).join(' '))
  ]
}

function formatAgentContent(value) {
  const content = cleanMarkdown(value)
  if (!content) return []
  return sourceOrderTableBlocks(content) || productTableBlocks(content) || plainBlocks(content)
}

module.exports = { formatAgentContent }
