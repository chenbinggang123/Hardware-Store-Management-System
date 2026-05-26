function pad(value) {
  return `${value}`.padStart(2, '0')
}

function formatMoney(value) {
  const amount = Number(value || 0)
  if (!Number.isFinite(amount)) {
    return '0.00'
  }
  return amount.toFixed(2)
}

function formatDateTime(value) {
  if (!value) {
    return '--'
  }
  const text = `${value}`.replace('T', ' ')
  return text.length > 16 ? text.slice(0, 16) : text
}

function formatDate(value = new Date()) {
  const date = value instanceof Date ? value : new Date(value)
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

module.exports = {
  formatMoney,
  formatDateTime,
  formatDate
}
