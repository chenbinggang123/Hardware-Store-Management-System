const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const root = path.resolve(__dirname, '..', 'miniprogram');
let js = 0, json = 0;
const errors = [];
function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const file = path.join(dir, e.name);
    if (e.isDirectory()) walk(file);
    else try {
      if (e.name.endsWith('.js')) { new vm.Script(fs.readFileSync(file, 'utf8'), { filename: file }); js++; }
      if (e.name.endsWith('.json')) { JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '')); json++; }
    } catch (error) { errors.push(error.message); }
  }
}
walk(root);
const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
// Per-page JSON and WXSS are optional in WeChat.
for (const p of app.pages) for (const ext of ['.js', '.wxml']) if (!fs.existsSync(path.join(root, p + ext))) errors.push('Missing page: ' + p + ext);
console.log(`Checked ${js} JavaScript files, ${json} JSON files, ${app.pages.length} registered pages.`);
if (errors.length) { console.error(errors.join('\n')); process.exitCode = 1; }
else console.log('Syntax and page files OK. WXML/WXSS still require WeChat compilation.');
