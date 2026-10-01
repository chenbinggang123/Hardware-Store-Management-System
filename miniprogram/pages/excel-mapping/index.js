const workflow = require('../../utils/excel-workflow')
const { openPage, showToast } = require('../../utils/router')

const targetOptions = ['商品名称', '规格', '单位', '数量', '单价', '条码', '备注', '忽略此列']

Page({
  data: { id: '', task: null, mappings: [], targetOptions },

  onLoad(options) {
    const task = workflow.getTask(options.id)
    if (!task) return showToast('文件任务不存在')
    this.setData({ id: options.id, task, mappings: task.mappings || [] })
  },

  changeTarget(event) {
    const index = Number(event.currentTarget.dataset.index)
    const mappings = this.data.mappings.slice()
    mappings[index] = { ...mappings[index], target: targetOptions[Number(event.detail.value)], confidence: 100 }
    this.setData({ mappings })
  },

  continueReview() {
    const missing = this.data.mappings.some((item) => item.required && item.target === '忽略此列')
    if (missing) return showToast('必填字段不能忽略')
    workflow.updateMappings(this.data.id, this.data.mappings)
    openPage('/pages/excel-review/index', { id: this.data.id }, { replace: true })
  }
})
