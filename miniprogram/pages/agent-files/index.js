const api = require('../../utils/api')
const { fileView, openAgentFile, confirmDeleteAgentFile } = require('../../utils/agent-files')
const { openPage, showToast } = require('../../utils/router')

function errorText(error, fallback) {
  return (error && (error.message || error.errMsg)) || fallback
}

Page({
  data: { files: [], loading: true, loadError: '', openingId: '', deletingId: '' },

  onShow() { this.loadFiles() },
  onPullDownRefresh() { this.loadFiles(true) },

  loadFiles(stopRefresh) {
    this.setData({ loading: true, loadError: '' })
    return api.getAgentOutputs().then((items) => {
      this.setData({ files: (items || []).map(fileView) })
    }).catch((error) => {
      this.setData({ loadError: errorText(error, '文件列表暂时无法加载') })
    }).finally(() => {
      this.setData({ loading: false })
      if (stopRefresh) wx.stopPullDownRefresh()
    })
  },

  retryLoad() { this.loadFiles() },
  openAgent() { openPage('/pages/agent/index') },

  openFile(event) {
    const file = this.data.files.find((item) => Number(item.id) === Number(event.currentTarget.dataset.id))
    if (!file || this.data.openingId) return
    if (!file.ready) return showToast(file.failed ? '这个文件生成失败，只能删除' : '文件还在生成中')
    this.setData({ openingId: file.id })
    openAgentFile(file)
      .catch((error) => showToast(errorText(error, '文件打开失败，请稍后重试')))
      .finally(() => this.setData({ openingId: '' }))
  },

  deleteFile(event) {
    const file = this.data.files.find((item) => Number(item.id) === Number(event.currentTarget.dataset.id))
    if (!file || this.data.deletingId) return
    this.setData({ deletingId: file.id })
    confirmDeleteAgentFile(file).then((deleted) => {
      if (!deleted) return
      this.setData({ files: this.data.files.filter((item) => Number(item.id) !== Number(file.id)) })
      showToast('文件已删除', 'success')
    }).catch((error) => showToast(errorText(error, '删除失败，请重试')))
      .finally(() => this.setData({ deletingId: '' }))
  }
})
