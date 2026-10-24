// 单据原图（拍照开单时保存的照片）：换临时链接、截出商品所在的那一行。
// 开单页、商品详情、工厂账本共用。

const cache = {}
const URL_TTL = 60 * 60 * 1000 // 云存储临时链接有时效，缓存一小时后重新换

function tempUrls(fileIds) {
  const wanted = Array.from(new Set((fileIds || []).filter(Boolean)))
  const now = Date.now()
  const missing = wanted.filter((id) => !cache[id] || cache[id].expires < now)
  if (!missing.length || !wx.cloud || !wx.cloud.getTempFileURL) {
    return Promise.resolve(urlMap(wanted))
  }
  const batches = []
  for (let i = 0; i < missing.length; i += 50) batches.push(missing.slice(i, i + 50))
  return Promise.all(batches.map((fileList) => wx.cloud.getTempFileURL({ fileList })
    .then((result) => (result.fileList || []).forEach((file) => {
      if (file && file.tempFileURL) cache[file.fileID] = { url: file.tempFileURL, expires: now + URL_TTL }
    }))
    .catch(() => null))).then(() => urlMap(wanted))
}

function urlMap(ids) {
  const map = {}
  ids.forEach((id) => { if (cache[id]) map[id] = cache[id].url })
  return map
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
