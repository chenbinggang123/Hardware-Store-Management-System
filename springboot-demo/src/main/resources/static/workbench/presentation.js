/* Human-readable tool previews. Untrusted values are never inserted as HTML. */
(() => {
  'use strict';
  const el = (tag, text, className) => {
    const node = document.createElement(tag);
    if (text != null) node.textContent = String(text);
    if (className) node.className = className;
    return node;
  };
  const labels = {
    productId:'商品编号', productName:'商品', name:'名称', barcode:'条码', spec:'规格', unit:'单位',
    quantity:'数量', beforeQuantity:'原库存', actualQuantity:'盘点后库存', changeQuantity:'库存变化', reason:'原因',
    customerId:'客户编号', customerName:'客户', supplierId:'供应商编号', supplierName:'供应商',
    counterpartyId:'往来方编号', counterpartyName:'往来方', amount:'金额', totalAmount:'合计金额',
    unitPrice:'单价', discount:'折扣', remark:'备注', note:'说明', status:'状态',
    title:'标题', fileName:'文件名', fileSize:'文件大小（字节）', sheetCount:'工作表数', rowCount:'数据行数',
    mismatchCount:'金额差异数', outputId:'文件编号', draftId:'草稿编号', draftVersion:'草稿版本',
    version:'版本', effect:'操作影响', retention:'文件保留', info:'单据信息', warnings:'核对提示',
    items:'商品明细', lines:'明细', rows:'数据', sheets:'工作表', columns:'列',
    date:'日期', documentDate:'单据日期', documentType:'单据类型', direction:'往来方向',
    receiveStock:'同时入库', ledgerId:'账本编号', ledgerName:'账本', factoryName:'工厂',
    paymentAmount:'收款金额', settlementMonth:'结算月份', orderNo:'订单号', total:'合计',
    costPrice:'成本价', retailPrice:'零售价', wholesalePrice:'批发价', stock:'库存',
    address:'地址', phone:'电话', contact:'联系人', sourceFactory:'源头厂商'
  };
  function valueText(value) {
    if (value == null || value === '') return '未填写';
    if (typeof value === 'boolean') return value ? '是' : '否';
    return String(value);
  }
  function rawDetails(value) {
    const details = el('details', null, 'preview-diagnostics');
    details.append(el('summary', '查看原始数据'), el('pre', JSON.stringify(value, null, 2)));
    return details;
  }
  function fields(entries) {
    const dl = el('dl', null, 'preview-fields');
    for (const [label, value] of entries) {
      const group = el('div'); group.append(el('dt', label), el('dd', valueText(value))); dl.append(group);
    }
    return dl;
  }
  function grid(columns, rows) {
    const wrap = el('div', null, 'table-scroll'); wrap.tabIndex = 0; wrap.setAttribute('aria-label', '待生成表格内容，可横向滚动');
    const table = el('table'); const head = el('thead'); const header = el('tr');
    columns.forEach(column => { const th = el('th', typeof column === 'object' ? column.header || column.name || '列' : column); th.scope = 'col'; header.append(th); });
    head.append(header); table.append(head); const body = el('tbody');
    rows.forEach(values => {
      const tr = el('tr');
      columns.forEach((column, index) => {
        const key = typeof column === 'object' ? column.header || column.name : column;
        const value = Array.isArray(values) ? values[index] : values?.[key];
        const cell = el('td');
        if (value && typeof value === 'object') cell.append(readable(value, 1)); else cell.textContent = value == null ? '' : String(value);
        tr.append(cell);
      }); body.append(tr);
    }); table.append(body); wrap.append(table); return wrap;
  }
  function workbook(data) {
    const content = el('div', null, 'workbook-preview');
    content.append(el('div', data.fileName || data.title || 'Excel 文件', 'preview-file-name'));
    const facts = [];
    if (data.sheetCount != null) facts.push(data.sheetCount + ' 个工作表');
    if (data.rowCount != null) facts.push(data.rowCount + ' 行数据');
    if (facts.length) content.append(el('p', facts.join(' · '), 'preview-summary'));
    if (Array.isArray(data.info) && data.info.length) content.append(fields(data.info.map(item => [item.label || '说明', item.value])));
    for (const sheet of data.sheets || []) {
      const section = el('section', null, 'preview-sheet'); section.append(el('h4', sheet.name || '工作表'));
      const rows = Array.isArray(sheet.sampleRows) ? sheet.sampleRows : [];
      if (Array.isArray(sheet.columns) && sheet.columns.length && rows.length) section.append(grid(sheet.columns, rows));
      if (sheet.rowCount != null) section.append(el('p', rows.length ? `预览 ${rows.length} 行，共 ${sheet.rowCount} 行` : `共 ${sheet.rowCount} 行`, 'preview-summary'));
      if (sheet.totalColumns?.length) section.append(el('p', '汇总列：' + sheet.totalColumns.join('、'), 'preview-summary'));
      content.append(section);
    }
    if (Number(data.mismatchCount) > 0) content.append(el('p', `有 ${data.mismatchCount} 处金额差异，请核对后再生成。`, 'preview-warning'));
    if (Array.isArray(data.warnings) && data.warnings.length) {
      const group = el('section', null, 'preview-warnings'); group.append(el('h4', '请核对'));
      const list = el('ul'); data.warnings.forEach(warning => list.append(el('li', warning))); group.append(list); content.append(group);
    }
    if (data.effect) content.append(el('p', data.effect, 'preview-effect'));
    if (data.retention) content.append(el('p', data.retention, 'preview-summary'));
    return content;
  }
  function readable(value, depth = 0) {
    if (depth > 8) return el('p', '内容层级较多，可在原始数据中查看完整内容。');
    if (value == null || typeof value !== 'object') return el('span', valueText(value));
    if (Array.isArray(value)) {
      if (!value.length) return el('span', '无');
      const list = el('ul', null, 'preview-list');
      value.forEach(item => { const li = el('li'); li.append(readable(item, depth + 1)); list.append(li); }); return list;
    }
    const container = el('div');
    const scalars = Object.entries(value).filter(([, item]) => item == null || typeof item !== 'object');
    if (scalars.length) container.append(fields(scalars.map(([key, item]) => [labels[key] || key, item])));
    for (const [key, item] of Object.entries(value)) {
      if (!item || typeof item !== 'object') continue;
      const section = el('section', null, 'preview-group'); section.append(el('h4', labels[key] || key), readable(item, depth + 1)); container.append(section);
    }
    return container;
  }
  function render(value) {
    let data = value;
    if (typeof data === 'string') { try { data = JSON.parse(data); } catch { return el('p', data); } }
    const container = el('div', null, 'operation-preview');
    if(data?.kind==='BILLING_DOCUMENT'){
      container.append(el('p',data.message||'开单草稿已准备好。'));
      if(/^billing\.html#document\/[1-9]\d*$/.test(data.editUrl||'')){const link=el('a','打开开单页，修改并生成 Excel','secondary-button');link.href=data.editUrl;container.append(link);}
    }else if(data?.kind==='BILLING_DRAFT'){
      const c=data.content||{};container.append(el('h4','先到开单页核对，再生成 Excel'),fields([['客户',c.customerName],['日期',c.date],['本店名称',c.companyName]]));
      container.append(grid(['商品名称','规格','单位','数量','单价','来源金额'],(c.lines||[]).map(r=>[r.name,r.specification,r.unit,r.quantity,r.price,r.sourceAmount])));
      if(c.sourceNote)container.append(el('p',c.sourceNote,'preview-warning'));container.append(el('p',data.effect,'preview-effect'));
    }else if (data?.kind === 'EXCEL_WORKBOOK') container.append(workbook(data)); else container.append(readable(data));
    if (data && typeof data === 'object') container.append(rawDetails(data));
    return container;
  }
  function parseReply(source) {
    // Recognize only server-authored envelope formats; ordinary prose and requested JSON stay intact.
    const text = String(source || '');
    const marker = '影响预览：'; const index = text.indexOf(marker);
    if (text.startsWith('即将执行“') && index >= 0) {
      try {
        const preview = JSON.parse(text.slice(index + marker.length));
        if (preview && typeof preview === 'object') return { text: preview.kind === 'EXCEL_WORKBOOK' ? '已整理好 Excel 内容，请核对预览后确认生成。' : '操作内容已准备好，请核对预览后确认。', preview };
      } catch { /* Preserve malformed or ordinary replies instead of discarding content. */ }
    }
    const prefix = '操作已成功执行。结果：';
    if (text.startsWith(prefix)) {
      try { return { text: '操作已完成。', preview: JSON.parse(text.slice(prefix.length)) }; } catch { /* Keep original text. */ }
    }
    return { text, preview: null };
  }
  window.WorkbenchPresentation = Object.freeze({ render, parseReply });
})();
