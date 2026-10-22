// 兼容旧入口：账款直接从工厂账本查看，不再维护对账或结清进度。
Page({ onLoad() { wx.redirectTo({ url: '/pages/factory-ledgers/index' }) } })
