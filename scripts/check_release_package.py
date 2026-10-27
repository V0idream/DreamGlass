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
    assert not any(n.startswith('assets/probes/') for n in entries), 'Debug shader fixture leaked into release'
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
    assert b'SamplingProbeActivity' not in dex and b'IconContrastProbeActivity' not in dex and b'BlurQualityProbeActivity' not in dex and b'MaterialStudyProbeActivity' not in dex and b'StabilityProbeActivity' not in dex, 'Debug probe leaked into release'

shader_path = 'app/src/main/kotlin/com/coloros16/liquidglass/liquidglass/LiquidGlassShader.kt'
shader = (root / shader_path).read_text(encoding='utf-8')
extract = lambda s: re.search(r'val AGSL_SOURCE: String = """(.*?)"""\.trimIndent\(\)', s, re.S).group(1)
# Restrict optical changes to the F16 direct-input branch and polished inner volume.
# Revert only the three explicitly marked COS17 optical refinements, then require exact 1.0.7 optics.
optics=extract(shader)
prior=(root/'docs/validation/1.0.8/1.0.7-baseline.agsl').read_text(encoding='utf-8')
old_coat=prior[prior.index('            float3 polishedRim ='):prior.index('            // [临时诊断 高光]')]
optics=re.sub(r'            // @DG-LENS-START.*?            // @DG-LENS-END\n','',optics,flags=re.S)
optics=optics.replace('if (-sdRaw >= lensHeight)','if (-sdRaw >= uRefractionHeight)')
optics=re.sub(r'                // @DG-PROFILE-START.*?                // @DG-PROFILE-END\n','',optics,flags=re.S)
optics=re.sub(r'            // @DG-COAT-START.*?            // @DG-COAT-END\n\n',lambda _:old_coat,optics,flags=re.S)
assert optics == prior, 'Unexpected optical change outside compact lens and directional coat'
assert 'uMaterialOpacity' not in shader and 'materialOpacity' not in shader
assert 'Copyright 2025 Kyant' in shader and '2026-10-23' in shader
changed = command('git', 'diff', '--name-only', baseline, '--', 'app/src/main/kotlin').splitlines()
allowed_hooks = {'BlurDrawHook.kt', 'QsSurfaceHook.kt', 'LocalTextContrast.kt',
                 'ButtonBackdropBlur.kt', 'ButtonBackdropWorker.kt', 'QsIconContrastHook.kt', 'QsIconContrastPolicy.kt', 'RuntimeWorkPolicy.kt'}
assert all('/hook/' not in f or Path(f).name in allowed_hooks for f in changed)

# 1.0.7: the old flag was misleadingly named isElementSource, and negating it bypassed
# every real snapshot. Check both production call sites as well as the Android binding probe.
hook=(root/'app/src/main/kotlin/com/coloros16/liquidglass/hook/BlurDrawHook.kt').read_text(encoding='utf-8')
assert 'isElementSource' not in hook
assert 'return RenderSource(snap.bitmap, srcRect, RectF(snap.region), isSnapshotSource = true)' in hook
assert 'bindQualityBackdrop(id, snapshot.bitmap, RectF(snapshot.region), srcRect,\n                    cg.isSnapshotSource, cg.params)' in hook
assert 'bindQualityBackdrop(id, source, renderSource.screenRect, srcRect,\n            renderSource.isSnapshotSource, rawParams)' in hook
assert 'shader, renderParams, sourceShader, lowResShader' in hook
assert 'val params = binding.params' in hook
assert 'sourceRect = binding.sourceRect' in hook

# 1.0.9: injected work is guarded and every capture executes off the main Looper.
assert hook.count('captureSnapshotImmediate("worker",') == 1
assert 'captureSnapshotImmediate("$logTag-main-thread")' not in hook
assert 'mainThreadSyncFirstFrame' not in hook
assert 'private val hostPostPending = java.util.concurrent.atomic.AtomicBoolean(false)' in hook
assert 'while (screenInteractive && Prefs.glassEnabled(api) && !isPowerSaveGlassOff())' in hook
assert 'if (!screenInteractive || !Prefs.glassEnabled(api)) return' in hook

# Built-in activation may write its selection flag, but cannot overwrite saved material values.
settings=(root/'app/src/main/kotlin/com/coloros16/liquidglass/ui/SettingsActivity.kt').read_text(encoding='utf-8')
ios_action=settings[settings.index('iosChip.setOnClickListener'):settings.index('bar.addView(iosChip)')]
assert 'putString(Prefs.KEY_BUILTIN_MATERIAL_PRESET, IosGlassPreset.ID)' in ios_action
assert '.putFloat(' not in ios_action and '.putBoolean(' not in ios_action
assert 'IosGlassPreset.resolve(m.builtinPreset, usePolishedMaterial(elementId)' in hook
assert 'pick(Prefs.KEY_BUTTON_BLUR_RADIUS, m.buttonBlurRadius)' in hook

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
assert sum(int(t.get('tests')) for t in unit) == 73
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
    'unitTestsPassed': 73, 'lintErrors': 0,
    'lintWarnings': sum(n.get('severity') == 'Warning' for n in lint),
    'parameterBindingLayoutsPreserved': layout_count,
    'opticalBaseline': '1.0.7 fixture', 'parameterBindingBaseline': baseline, 'opticalChangesLimitedToMarkedLensAndCoat': True, 'materialAlphaControlRemoved': True, 'snapshotBindingFirstDrawAndRefreshChecked': True,
    'physicalDeviceAccessed': False, 'colorOsRuntimeTested': False,
}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2))
