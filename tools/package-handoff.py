"""Build an allowlisted source snapshot. No Git history, credentials or real photos."""
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[1]
target = root / 'outputs' / 'hardware-store-collaboration.zip'
target.parent.mkdir(exist_ok=True)
files = set()
for name in ['README.md', 'CONTRIBUTING.md', 'agent.md', '.gitignore', '.gitattributes', 'package.json', 'run-dev.ps1']:
    files.add(root / name)
for folder in ['miniprogram', 'springboot-demo/src', 'docs/collaboration', 'docs/商品百科']:
    files.update(p for p in (root / folder).rglob('*') if p.is_file())
for name in ['pom.xml', 'Dockerfile', '.dockerignore', 'mvnw', 'mvnw.cmd', 'run-dev.ps1', 'agent.local.env.example']:
    files.add(root / 'springboot-demo' / name)
for name in ['docs/agent/08-Excel文件任务后端接口.md', 'docs/当前实现与前后端修正说明.md', 'tools/package-handoff.py', 'tools/check-project.cjs', 'tools/live-smoke.cjs']:
    files.add(root / name)
files.update((root / 'tools').glob('*.test.cjs'))
allowed = []
for p in files:
    if not p.is_file() or p.name == 'project.private.config.json':
        continue
    rel = p.relative_to(root)
    if any(part in {'.git', 'node_modules', '__pycache__', 'target', '.m2', '.m2repo'} for part in rel.parts):
        continue
    if p.suffix.lower() in {'.pem', '.key', '.log', '.pyc'} or 'credentials' in p.name.lower():
        continue
    allowed.append(p)
with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as z:
    for p in sorted(allowed):
        z.write(p, 'hardware-store/' + p.relative_to(root).as_posix())
print(f'{len(allowed)} files -> {target}')
