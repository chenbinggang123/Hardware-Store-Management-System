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
  const text = cleanMarkdown(value)
    .replace(/\|\s*:?-{3,}:?\s*(?=\||$)/g, '')
    .replace(/\s+-\s+(?=[^：:\n]{1,18}[：:])/g, '\n')
    .replace(/\s*\|\s*/g, ' · ')

  return text.split(/\n+/).map((line) => line.trim()).filter(Boolean).map((line) => {
    const field = line.match(/^-?\s*([^：:]{1,18})[：:]\s*(.+)$/)
    if (field) return { type: 'field', label: field[1].trim(), value: field[2].trim() }
    return { type: 'text', text: line.replace(/^[-•]\s*/, '') }
  })
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
  return productTableBlocks(content) || plainBlocks(content)
}

module.exports = { formatAgentContent }
