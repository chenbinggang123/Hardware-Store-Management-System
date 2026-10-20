/* Small, deliberately HTML-free Markdown renderer. Every untrusted value is a text node. */
(() => {
  'use strict';
  function node(tag, text) {
    const el = document.createElement(tag);
    if (text !== undefined) el.textContent = text;
    return el;
  }
  function inline(parent, source, depth = 0) {
    if (depth > 8) { parent.append(document.createTextNode(source)); return; }
    const tokens = /(`+)([^`\n]+)\1|\*\*([^\n]+?)\*\*|__([^\n]+?)__|\*([^*\n]+?)\*|~~([^\n]+?)~~|\[([^\]\n]+)\]\(([^\s)]+)\)/g;
    let cursor = 0;
    for (const match of source.matchAll(tokens)) {
      parent.append(document.createTextNode(source.slice(cursor, match.index)));
      let el;
      if (match[1]) el = node('code', match[2]);
      else if (match[3] || match[4]) { el = node('strong'); inline(el, match[3] || match[4], depth + 1); }
      else if (match[5]) { el = node('em'); inline(el, match[5], depth + 1); }
      else if (match[6]) { el = node('del'); inline(el, match[6], depth + 1); }
      else {
        try {
          const url = new URL(match[8]);
          if (!['https:', 'http:'].includes(url.protocol)) throw new Error('Unsafe URL');
          el = node('a', match[7]); el.href = url.href; el.target = '_blank'; el.rel = 'noopener noreferrer';
        } catch { el = document.createTextNode(match[0]); }
      }
      parent.append(el); cursor = match.index + match[0].length;
    }
    parent.append(document.createTextNode(source.slice(cursor)));
  }
  function cells(line) { return line.trim().replace(/^\|/, '').replace(/\|$/, '').split(/(?<!\\)\|/).map(s => s.trim().replace(/\\\|/g, '|')); }
  function isTableRule(line) { const parts = cells(line || ''); return parts.length > 1 && parts.every(s => /^:?-{3,}:?$/.test(s)); }
  function render(source) {
    const fragment = document.createDocumentFragment();
    const lines = String(source || '').replace(/\r\n?/g, '\n').split('\n');
    let i = 0;
    while (i < lines.length) {
      const line = lines[i];
      if (!line.trim()) { i++; continue; }
      const fence = line.match(/^\s*(`{3,}|~{3,})(.*)$/);
      if (fence) {
        const contents = []; i++;
        while (i < lines.length && !new RegExp('^\\s*' + fence[1][0] + '{' + fence[1].length + ',}\\s*$').test(lines[i])) contents.push(lines[i++]);
        i++; const pre = node('pre'); pre.tabIndex = 0; pre.setAttribute('aria-label', '代码'); pre.append(node('code', contents.join('\n'))); fragment.append(pre); continue;
      }
      const heading = line.match(/^\s{0,3}(#{1,6})\s+(.+?)\s*#*$/);
      if (heading) { const el = node('h' + Math.min(heading[1].length + 1, 4)); inline(el, heading[2]); fragment.append(el); i++; continue; }
      if (/^\s*(?:-{3,}|\*{3,}|_{3,})\s*$/.test(line)) { fragment.append(node('hr')); i++; continue; }
      if (line.includes('|') && isTableRule(lines[i + 1])) {
        const heads = cells(line); const alignment = cells(lines[i + 1]);
        const wrap = node('div'); wrap.className = 'table-scroll'; wrap.tabIndex = 0; wrap.setAttribute('aria-label', '表格，可横向滚动');
        const table = node('table'); const thead = node('thead'); const tr = node('tr');
        heads.forEach(s => { const th = node('th'); th.scope = 'col'; inline(th, s); tr.append(th); });
        thead.append(tr); table.append(thead); const tbody = node('tbody'); i += 2;
        while (i < lines.length && lines[i].trim() && lines[i].includes('|')) {
          const row = node('tr'); const values = cells(lines[i++]);
          heads.forEach((_, c) => { const td = node('td'); inline(td, values[c] || ''); if (alignment[c]?.endsWith(':')) td.style.textAlign = alignment[c].startsWith(':') ? 'center' : 'right'; row.append(td); });
          tbody.append(row);
        }
        table.append(tbody); wrap.append(table); fragment.append(wrap); continue;
      }
      if (/^\s*>/.test(line)) {
        const quote = []; while (i < lines.length && /^\s*>/.test(lines[i])) quote.push(lines[i++].replace(/^\s*>\s?/, ''));
        const block = node('blockquote'); block.append(render(quote.join('\n'))); fragment.append(block); continue;
      }
      const list = line.match(/^\s*(?:([-+*])|(\d+)[.)])\s+(.+)$/);
      if (list) {
        const ordered = !!list[2]; const ul = node(ordered ? 'ol' : 'ul'); if (ordered) ul.start = Number(list[2]);
        while (i < lines.length) {
          const item = lines[i].match(/^\s*(?:([-+*])|(\d+)[.)])\s+(.+)$/);
          if (!item || !!item[2] !== ordered) break;
          const li = node('li'); inline(li, item[3]); ul.append(li); i++;
          while (i < lines.length && /^\s{2,}\S/.test(lines[i]) && !/^\s*(?:[-+*]|\d+[.)])\s+/.test(lines[i])) { li.append(node('br')); inline(li, lines[i++].trim()); }
        }
        fragment.append(ul); continue;
      }
      const paragraph = node('p'); inline(paragraph, line); i++;
      while (i < lines.length && lines[i].trim() && !/^\s*(?:#{1,6}\s|>|`{3,}|~{3,}|[-+*]\s|\d+[.)]\s|---)/.test(lines[i]) && !(lines[i].includes('|') && isTableRule(lines[i + 1]))) { paragraph.append(node('br')); inline(paragraph, lines[i++]); }
      fragment.append(paragraph);
    }
    return fragment;
  }
  window.WorkbenchMarkdown = Object.freeze({ render });
})();
