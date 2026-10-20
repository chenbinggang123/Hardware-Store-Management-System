/* File library and a read-only view of cells from the stored XLSX, not the generation preview. */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const el = (tag, text, className) => { const node = document.createElement(tag); if (text != null) node.textContent = String(text); if (className) node.className = className; return node; };
  const icon = () => { const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg'); const use = document.createElementNS(svg.namespaceURI, 'use'); use.setAttribute('href', '#i-file'); svg.append(use); svg.setAttribute('aria-hidden','true'); return svg; };
  const columnName = index => { let label = ''; for (let value = index + 1; value; value = Math.floor((value - 1) / 26)) label = String.fromCharCode(65 + (value - 1) % 26) + label; return label; };
  const size = bytes => bytes >= 1048576 ? (bytes / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.ceil((bytes || 0) / 1024)) + ' KB';
  const statuses = { READY:'已生成',MISSING:'原文件已缺失，请重新生成',FAILED:'生成失败',PROCESSING:'正在生成' };
  function create({ request, download, isAuthenticated }) {
    let serial = 0, files = [], currentFile = null, view = null, loading = false;
    const authenticated = () => isAuthenticated();
    function empty(message, retry) {
      const content = el('div', null, 'file-empty'); content.append(el('p', message));
      if (retry) { const button = el('button', '重新加载', 'secondary-button'); button.type = 'button'; button.addEventListener('click', retry); content.append(button); }
      return content;
    }
    function requireLogin(container) {
      const content = empty('请先登录，查看属于你的生成文件。'); const button = el('button', '前往登录', 'secondary-button');
      button.type = 'button'; button.addEventListener('click', () => { location.hash = 'chat'; }); content.append(button); container.replaceChildren(content);
    }
    function renderList() {
      const keyword = $('fileSearch').value.trim().toLocaleLowerCase();
      const matches = files.filter(file => (file.name || '').toLocaleLowerCase().includes(keyword));
      $('fileCount').textContent = `${matches.length} 个文件`;
      $('fileList').replaceChildren();
      if (!matches.length) { $('fileList').append(empty(keyword ? '没有匹配的文件，请换个关键词。' : '还没有生成文件。确认生成 Excel 后，文件会保存在这里，也会出现在对话回复下方。')); return; }
      for (const file of matches) {
        const row = el('div', null, 'library-row'); const open = el('button', null, 'library-open'); open.type = 'button';
        const label = el('span'); label.append(el('strong', file.name || 'Excel 文件'));
        const meta = [statuses[file.status] || '暂不可用', size(file.size)];
        if (file.createTime) meta.push(file.createTime.replace('T',' ').slice(0,16));
        label.append(el('small', meta.join(' · '))); open.append(icon(), label); open.title = file.name || '查看文件';
        open.disabled = file.status !== 'READY'; open.addEventListener('click', () => { location.hash = 'file/' + file.id; });
        const action = el('button', '预览', 'secondary-button'); action.type = 'button'; action.disabled = file.status !== 'READY'; action.addEventListener('click', () => { location.hash = 'file/' + file.id; });
        row.append(open, action); $('fileList').append(row);
      }
    }
    async function loadFiles() {
      const seq = ++serial; currentFile = null; view = null;
      if (!authenticated()) { requireLogin($('fileList')); $('fileCount').textContent = ''; return; }
      $('refreshFiles').disabled = true; $('fileList').replaceChildren(empty('正在读取你的文件…'));
      try {
        const response = await request('/agent/outputs'); if (seq !== serial) return;
        files = Array.isArray(response.data) ? response.data : []; renderList();
      } catch (error) { if (seq === serial) { $('fileList').replaceChildren(empty(error.message, loadFiles)); $('fileCount').textContent = ''; } }
      finally { if (seq === serial) $('refreshFiles').disabled = false; }
    }
    function status(message, error, retry) {
      $('workbookStatus').className = 'workbook-status' + (error ? ' error' : ''); $('workbookStatus').replaceChildren(document.createTextNode(message));
      if (retry) { const button = el('button', '重试', 'text-button'); button.type = 'button'; button.addEventListener('click', retry); $('workbookStatus').append(button); }
    }
    function setLoading(value) {
      loading = value; $('workbookGrid').setAttribute('aria-busy', String(value));
      $('previousRows').disabled = value || !view || view.offset === 0;
      $('nextRows').disabled = value || !view?.hasMore;
      $('workbookSheets').querySelectorAll('button').forEach(button => { button.disabled = value; });
    }
    function renderSheets(data) {
      $('workbookSheets').replaceChildren();
      for (const sheet of data.sheets || []) {
        const button = el('button', sheet.name); button.type = 'button'; button.setAttribute('role','tab');
        button.setAttribute('aria-selected',String(sheet.index === data.sheetIndex)); button.setAttribute('aria-controls','workbookGrid');
        button.tabIndex = sheet.index === data.sheetIndex ? 0 : -1;
        button.addEventListener('click', () => { if (!loading) loadSheet(sheet.index,0); });
        button.addEventListener('keydown', event => {
          if (!['ArrowLeft','ArrowRight','Home','End'].includes(event.key) || loading) return;
          event.preventDefault(); const items = data.sheets || []; let index = items.findIndex(item => item.index === sheet.index);
          index = event.key === 'Home' ? 0 : event.key === 'End' ? items.length-1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + items.length) % items.length;
          loadSheet(items[index].index,0,true);
        }); $('workbookSheets').append(button);
      }
    }
    function renderGrid(data) {
      $('workbookGrid').classList.toggle('receipt-preview',data.presentation==='RECEIPT');
      $('workbookGrid').replaceChildren();
      if (!(data.rows || []).length) { $('workbookGrid').append(empty('这个工作表还没有内容。')); return; }
      const table = el('table'); table.setAttribute('aria-label',data.sheetName || '工作表');
      const colgroup = el('colgroup'); const numbered = el('col'); numbered.style.width = '46px'; colgroup.append(numbered);
      for (let col = 0; col < data.shownColumns; col++) { const item = el('col'); item.style.width = (data.columnWidths?.[col] || 110) + 'px'; colgroup.append(item); } table.append(colgroup);
      const head = el('thead'), header = el('tr'); header.append(el('th','', 'row-number'));
      for (let col = 0; col < data.shownColumns; col++) { const cell = el('th',columnName(col)); cell.scope='col'; header.append(cell); }
      head.append(header); table.append(head); const body = el('tbody');
      const formats = new Map((data.formats || []).map(format => [format.row+':'+format.column,format]));
      const spans = new Map(); const covered = new Set();
      for (const merge of data.merges || []) {
        const first = Math.max(merge.firstRow,data.offset), last = Math.min(merge.lastRow,data.offset+data.rows.length-1);
        const lastCol = Math.min(merge.lastColumn,data.shownColumns-1);
        if (first > last || merge.firstColumn > lastCol) continue;
        spans.set(first+':'+merge.firstColumn,{rows:last-first+1,columns:lastCol-merge.firstColumn+1,text:merge.text});
        for (let row=first;row<=last;row++) for (let col=merge.firstColumn;col<=lastCol;col++) if (row!==first || col!==merge.firstColumn) covered.add(row+':'+col);
      }
      data.rows.forEach((values,index) => {
        const rowNumber=data.offset+index; const row=el('tr');const label=el('th',rowNumber+1,'row-number');label.scope='row';row.append(label);
        for (let col=0;col<data.shownColumns;col++) {
          const key=rowNumber+':'+col;if(covered.has(key))continue;const span=spans.get(key);
          const cell=el('td',span ? span.text : values[col] || ''); const format=formats.get(key);
          if(format?.bold)cell.classList.add('bold');if(format?.numeric)cell.classList.add('numeric');
          if(format?.alignment && ['LEFT','CENTER','RIGHT','JUSTIFY'].includes(format.alignment))cell.style.textAlign=format.alignment.toLowerCase();
          if(Number(format?.fontSize)>0)cell.style.fontSize=Math.max(12,Math.min(Number(format.fontSize)*4/3,26))+'px';
          if(format?.alignment)cell.style.backgroundColor=format.warning?'#fff5c4':'#fff';
          if(span){cell.rowSpan=span.rows;cell.colSpan=span.columns;}row.append(cell);
        } body.append(row);
      }); table.append(body); $('workbookGrid').append(table); $('workbookGrid').scrollTop=0; $('workbookGrid').scrollLeft=0;
    }
    async function loadSheet(sheet=0,offset=0,focusTab=false) {
      if (!currentFile || !authenticated()) return;
      const file=currentFile, seq=++serial; setLoading(true);status('正在读取工作表…');
      try {
        const response=await request('/agent/outputs/'+file.id+'/preview?sheet='+sheet+'&offset='+offset+'&limit=100');
        if(seq!==serial)return;view=response.data;
        renderGrid(view);renderSheets(view);
        $('workbookSubtitle').textContent=`${view.sheets.length} 个工作表 · 当前：${view.sheetName}`;
        status(view.columnsTruncated ? `当前显示前 ${view.shownColumns} 列，共 ${view.columnCount} 列；完整内容请下载原文件。` : `文件只读预览 · 商品单据可点击「在开单页修改」，核对后生成新文件 · 共 ${view.columnCount} 列，可左右滚动`);
        $('workbookPageInfo').textContent=view.totalRows ? `第 ${view.offset+1}–${view.offset+view.rows.length} 行，共 ${view.totalRows} 行` : '空工作表';
        if(focusTab)$('workbookSheets').querySelector('[aria-selected="true"]')?.focus();
      } catch(error) {
        if(seq!==serial)return;view=null;$('workbookGrid').replaceChildren(empty('这次未能读取表格内容。'));$('workbookPageInfo').textContent='';
        status(error.message,true,()=>loadSheet(sheet,offset));
      } finally {if(seq===serial)setLoading(false);}
    }
    async function openFile(id) {
      const seq=++serial;currentFile=null;view=null;$('editWorkbook').hidden=true;$('downloadWorkbook').disabled=true;$('workbookTitle').textContent='文件预览';
      $('workbookSubtitle').textContent='正在读取实际文件…';$('workbookSheets').replaceChildren();$('workbookPageInfo').textContent='';setLoading(true);
      if(!authenticated()){requireLogin($('workbookGrid'));status('登录后即可查看文件。');setLoading(false);return;}
      $('workbookGrid').replaceChildren(empty('正在打开 Excel…'));status('正在读取文件信息…');
      try {
        const response=await request('/agent/outputs/'+id);if(seq!==serial)return;
        currentFile=response.data;$('workbookTitle').textContent=currentFile.name || 'Excel 文件';$('workbookTitle').title=currentFile.name || '';
        if(currentFile.status!=='READY')throw new Error(currentFile.errorMessage || statuses[currentFile.status] || '文件暂不可用');
        $('downloadWorkbook').disabled=false;$('editWorkbook').href='billing.html#import/'+id;$('editWorkbook').hidden=false;await loadSheet();
      }catch(error){if(seq!==serial)return;setLoading(false);status(error.message,true,()=>openFile(id));$('workbookGrid').replaceChildren(empty('无法打开此文件，请检查文件状态或稍后重试。'));}
    }
    async function route(hash) {
      ++serial;loading=false;
      const match=hash.match(/^#file\/([1-9]\d*)$/);
      if(hash==='#files')await loadFiles();else if(match)await openFile(match[1]);
    }
    function reset() { ++serial;files=[];currentFile=null;view=null;loading=false;$('fileList').replaceChildren();$('workbookGrid').replaceChildren();$('workbookSheets').replaceChildren();$('downloadWorkbook').disabled=true; }
    $('fileSearch').addEventListener('input',()=>{if(authenticated())renderList();});
    $('refreshFiles').addEventListener('click',loadFiles);$('backFromWorkbook').addEventListener('click',()=>{location.hash='files';});
    $('previousRows').addEventListener('click',()=>{if(!loading && view)loadSheet(view.sheetIndex,Math.max(0,view.offset-view.limit));});
    $('nextRows').addEventListener('click',()=>{if(!loading && view?.hasMore)loadSheet(view.sheetIndex,view.offset+view.limit);});
    $('downloadWorkbook').addEventListener('click',async()=>{if(currentFile && authenticated())await download(currentFile,$('downloadWorkbook'));});
    return {route,reset};
  }
  window.WorkbenchFiles=Object.freeze({create});
})();
