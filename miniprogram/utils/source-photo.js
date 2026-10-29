// 单据原图（拍照开单时保存的照片）：换临时链接、截出商品所在的那一行。
// 开单页、商品详情、工厂账本共用。

const config = require('../config/cloud')
let cache = {}, cacheToken = ''
function tempUrls(fileIds) {
  const session = wx.getStorageSync('auth_session') || {}
  if (cacheToken !== session.token) { cache = {}; cacheToken = session.token }
  const wanted = Array.from(new Set((fileIds || []).filter(Boolean)))
  return Promise.all(wanted.map(id => {
    if (cache[id]) return null
    let path
    if (id.startsWith('attachment://')) path = '/agent/attachments/' + encodeURIComponent(id.slice(13)) + '/image'
    else if (id.startsWith('server://media/product-images/')) path = '/media/product-images/' + id.split('/').slice(-2).map(encodeURIComponent).join('/')
    else return null
    return new Promise((resolve, reject) => wx.request({
      url: config.baseURL + config.apiPrefix + path, responseType: 'arraybuffer',
      header: { Authorization: 'Bearer ' + (session.token || '') },
      success(result) {
        if (result.statusCode !== 200) { reject(new Error('图片读取失败（' + result.statusCode + '）')); return }
        const filePath = wx.env.USER_DATA_PATH + '/server-photo-' + Date.now() + '-' + Math.random().toString(36).slice(2) + '.jpg'
        wx.getFileSystemManager().writeFile({ filePath, data: result.data, success() { cache[id] = filePath; resolve() }, fail: reject })
      }, fail: reject
    }))
  })).then(() => Object.fromEntries(wanted.filter(id => cache[id]).map(id => [id, cache[id]])))
}

// 把原图上某一行截出来：外层容器按行的高度裁剪，里面放大后的整张图负偏移。
function rowCrop(box, naturalWidth, naturalHeight, width) {
  if (!box || box.length !== 4 || !naturalWidth || !naturalHeight || !width) return null
  const pad = 8
  const left = Math.max(0, box[0] - pad)
  const top = Math.max(0, box[1] - pad)
  const right = Math.min(naturalWidth, box[2] + pad)
  const bottom = Math.min(naturalHeight, box[3] + pad)
  if (right <= left || bottom <= top) return null
  const scale = width / (right - left)
  return {
    height: Math.round((bottom - top) * scale),
    imageStyle: `width:${Math.round(naturalWidth * scale)}px;height:${Math.round(naturalHeight * scale)}px;margin-left:-${Math.round(left * scale)}px;margin-top:-${Math.round(top * scale)}px;`
  }
}

// 页面上某个宽度（rpx）换算成 px，给截图用
function pxFromRpx(rpx) {
  const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
  return Math.round(info.windowWidth * rpx / 750)
}

function preview(url, urls) {
  if (url) wx.previewImage({ current: url, urls: urls && urls.length ? urls : [url] })
}

module.exports = { tempUrls, rowCrop, pxFromRpx, preview }
