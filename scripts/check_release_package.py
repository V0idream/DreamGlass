"""Verify release identity, bundled legal texts and the retained rendering baseline. No device access."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

p = argparse.ArgumentParser()
p.add_argument('--apk', type=Path, required=True)
p.add_argument('--aapt2', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
args = p.parse_args()
root = Path(__file__).resolve().parents[1]
baseline = '25c47e7'
gradle = (root / 'app/build.gradle.kts').read_text(encoding='utf-8')
version = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)[1]
version_code = int(re.search(r'versionCode\s*=\s*([\d_]+)', gradle)[1].replace('_', ''))

def command(*parts):
    return subprocess.check_output(list(map(str, parts)), cwd=root).decode('utf-8')

def git_file(path):
    return command('git', 'show', baseline + ':' + path).replace('\r\n', '\n')

badging = command(args.aapt2, 'dump', 'badging', args.apk.resolve())
assert f"package: name='com.dreamglass.cos17' versionCode='{version_code}' versionName='{version}'" in badging
assert "application-label:'DreamGlass'" in badging
assert 'application-debuggable' not in badging
with zipfile.ZipFile(args.apk) as apk:
    assert apk.testzip() is None
    entries = apk.namelist()
    assert not any(n.startswith('lib/') for n in entries), 'Unexpected native binary'
    assert not any(n.startswith('assets/licenses/') for n in entries), 'Obsolete legal asset'
    assert not any('sponsor_qr' in n for n in entries), 'Obsolete donation asset'
    for source, asset in [('LICENSE', 'GPL-3.0.txt'), ('licenses/Apache-2.0.txt', 'Apache-2.0.txt'),
                          ('NOTICE', 'NOTICE.txt'), ('THIRD-PARTY-NOTICES.md', 'THIRD-PARTY-NOTICES.txt'),
                          ('licenses/dependency-inventory.json', 'dependency-inventory.json')]:
        assert apk.read('assets/legal/' + asset) == (root / source).read_bytes(), asset
    inventory = json.loads(apk.read('assets/legal/dependency-inventory.json'))
    assert len(inventory['components']) == 57
    assert inventory['release'] == 'DreamGlass ' + version
    assert all(c['license'] == 'Apache-2.0' for c in inventory['components'])
    for notice in inventory['retainedOriginalNotices']:
        data = apk.read('assets/legal/third-party/' + notice['copy'])
        assert hashlib.sha256(data).hexdigest() == notice['sha256']
    dex = b''.join(apk.read(n) for n in entries if re.fullmatch(r'classes\d*\.dex', n))
    assert b'com/coloros16/liquidglass/update/UpdateChecker;' not in dex

shader_path = 'app/src/main/kotlin/com/coloros16/liquidglass/liquidglass/LiquidGlassShader.kt'
shader = (root / shader_path).read_text(encoding='utf-8')
extract = lambda s: re.search(r'val AGSL_SOURCE: String = """(.*?)"""\.trimIndent\(\)', s, re.S).group(1)
optics = extract(shader).replace('        uniform float uMaterialOpacity; // Premultiplied material coverage; content is drawn separately.\n', '')
optics = optics.replace('return half4(clamp(glass, 0.0, 1.0) * clamp(uMaterialOpacity, 0.0, 1.0));',
                       'return half4(clamp(glass, 0.0, 1.0));')
assert optics == extract(git_file(shader_path)), 'Unexpected optical algorithm change beyond material opacity'
assert 'Copyright 2025 Kyant' in shader and '2026-10-23' in shader
changed = command('git', 'diff', '--name-only', baseline, '--', 'app/src/main/kotlin').splitlines()
allowed_hooks = {'BlurDrawHook.kt', 'QsSurfaceHook.kt', 'LocalTextContrast.kt'}
assert all('/hook/' not in f or Path(f).name in allowed_hooks for f in changed)

def ids(text):
    return set(re.findall(r'@\+id/([A-Za-z0-9_]+)', text))

layout_count = 0
for layout in (root / 'app/src/main/res/layout').glob('view_*.xml'):
    if layout.name == 'view_cat_about.xml':
        continue
    relative = layout.relative_to(root).as_posix()
    assert ids(git_file(relative)) <= ids(layout.read_text(encoding='utf-8')), relative + ' binding IDs removed'
    layout_count += 1

unit = [ET.parse(f).getroot() for f in (root / 'app/build/test-results/testReleaseUnitTest').glob('TEST-*.xml')]
assert sum(int(t.get('tests')) for t in unit) == 32
assert sum(int(t.get('failures')) + int(t.get('errors')) for t in unit) == 0
lint = ET.parse(root / 'app/build/reports/lint-results-release.xml').getroot()
lint_errors = [n for n in lint if n.get('severity') in ('Error', 'Fatal')]
assert not lint_errors
report = {
    'product': 'DreamGlass', 'supportedRelease': 'For ColorOS 17', 'version': version,
    'versionCode': version_code, 'package': 'com.dreamglass.cos17', 'debuggable': False,
    'apkSha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(),
    'runtimeComponents': len(inventory['components']),
    'retainedNoticeEntries': len(inventory['retainedOriginalNotices']),
    'unitTestsPassed': 32, 'lintErrors': 0,
    'lintWarnings': sum(n.get('severity') == 'Warning' for n in lint),
    'parameterBindingLayoutsPreserved': layout_count,
    'opticalBaseline': baseline, 'opticsUnchangedExceptOpacity': True,
    'physicalDeviceAccessed': False, 'colorOsRuntimeTested': False,
}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2))
