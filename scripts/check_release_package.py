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
p.add_argument('--build-dir', type=Path, default=Path('app/build'))
args = p.parse_args()
root = Path(__file__).resolve().parents[1]
build_dir = args.build_dir.resolve()
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
    assert b'AppGlassDecorDrawable' not in dex, 'Retired frame overlay leaked into release'
    assert b'AppGlassEffect' in dex and b'SharedGlassParams' in dex
    assert b'ParticleDissolveHook' in dex and b'HardwareBackdropBlur' in dex and b'KeyguardGlassHook' in dex and b'LauncherTiltCompat' in dex and b'NotificationTiltCompat' in dex
    assert b'SamplingProbeActivity' not in dex and b'IconContrastProbeActivity' not in dex and b'BlurQualityProbeActivity' not in dex and b'MaterialStudyProbeActivity' not in dex and b'StabilityProbeActivity' not in dex and b'HardwareBlurProbeActivity' not in dex and b'AppGlassProbeShader' not in dex and b'ProbeVolumeView' not in dex and b'ParticleProbeActivity' not in dex and b'KeyguardGlassProbeActivity' not in dex and b'MotionProbeActivity' not in dex and b'SceneGlassProbeActivity' not in dex and b'ClearAllCloseProbeActivity' not in dex and b'ShortcutLayerProbeActivity' not in dex and b'KeyguardGlyphAudit;' not in dex and b'RecentsParticleProbeActivity' not in dex, 'Debug probe leaked into release'

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
assert [line for line in optics.splitlines() if line.strip()] == [line for line in prior.splitlines() if line.strip()], 'Unexpected optical change outside compact lens and directional coat'
assert 'uMaterialOpacity' not in shader and 'materialOpacity' not in shader
assert 'Copyright 2025 Kyant' in shader and '2026-10-23' in shader
changed = command('git', 'diff', '--name-only', baseline, '--', 'app/src/main/kotlin').splitlines()
allowed_hooks = {'BlurDrawHook.kt', 'QsSurfaceHook.kt', 'LocalTextContrast.kt',
                 'ButtonBackdropBlur.kt', 'ButtonBackdropWorker.kt', 'QsIconContrastHook.kt', 'QsIconContrastPolicy.kt', 'RuntimeWorkPolicy.kt', 'AppGlassDecorDrawable.kt', 'AppGlassHook.kt',
                 'HostVersion.kt', 'AppGlassEffect.kt', 'AppGlassSurfacePolicy.kt', 'LauncherTiltCompat.kt', 'NotificationTiltCompat.kt', 'LauncherHook.kt', 'VolumeMaskGuard.kt', 'HardwareBackdropBlur.kt',
                 'BackgroundCadence.kt', 'CosCompatibility.kt', 'PerClassCache.kt', 'SnapshotFingerprint.kt', 'ParticleDissolveHook.kt', 'KeyguardGlassHook.kt', 'KeyguardGlassAttachment.kt', 'KeyguardGlassMaterial.kt', 'KeyguardGlassPolicy.kt', 'KeyguardLayeredShortcut.kt', 'NativeAppBackdropHook.kt', 'RecentsParticleHook.kt'}
assert all('/hook/' not in f or Path(f).name in allowed_hooks for f in changed)

# 1.0.7: the old flag was misleadingly named isElementSource, and negating it bypassed
# every real snapshot. Check both production call sites as well as the Android binding probe.
hook=(root/'app/src/main/kotlin/com/coloros16/liquidglass/hook/BlurDrawHook.kt').read_text(encoding='utf-8')
assert 'isElementSource' not in hook
assert 'return RenderSource(snap.bitmap, srcRect, RectF(snap.region), isSnapshotSource = true)' in hook
assert 'bindQualityBackdrop(id, snapshot.bitmap, RectF(snapshot.region), srcRect,\n                    cg.isSnapshotSource, cg.params)' in hook
assert 'bindQualityBackdrop(id, source, renderSource.screenRect, srcRect,\n            renderSource.isSnapshotSource, rawParams)' in hook
assert 'shader, bindParams, sourceShader, lowResShader' in hook
assert 'val params = binding.params' in hook
assert 'srcRect = binding.sourceRect' in hook and 'val bindSrcRect = binding.sourceRect' in hook and 'sourceRect = bindSrcRect' in hook

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
    if layout.name in {'view_cat_corner_radius.xml', 'view_cat_components.xml', 'view_cat_keyguard.xml'}:
        assert ids(layout.read_text(encoding='utf-8')), relative
    elif layout.name == 'view_cat_log.xml':
        # Upstream 0.3 replaces the retired FileLog controls with explicit logcat capture.
        assert {'captureStartRow', 'captureStopRow'} <= ids(layout.read_text(encoding='utf-8'))
    else:
        assert ids(git_file(relative)) <= ids(layout.read_text(encoding='utf-8')), relative + ' binding IDs removed'
    layout_count += 1

unit = [ET.parse(f).getroot() for f in (build_dir / 'test-results/testReleaseUnitTest').glob('TEST-*.xml')]
unit_count = sum(int(t.get('tests')) for t in unit)
assert unit_count >= 124, 'Missing current compatibility regressions'
assert sum(int(t.get('failures')) + int(t.get('errors')) for t in unit) == 0
lint = ET.parse(build_dir / 'reports/lint-results-release.xml').getroot()
lint_errors = [n for n in lint if n.get('severity') in ('Error', 'Fatal')]
assert not lint_errors

# 1.1.0: exact host scopes, no automatic internet updater, no COS16 volume draw overrides on COS17.
scopes=[line.strip() for line in (root/'app/src/main/resources/META-INF/xposed/scope.list').read_text(encoding='utf-8').splitlines() if line.strip() and not line.lstrip().startswith('#')]
assert set(scopes)=={'com.android.systemui','com.android.launcher','com.coloros.gallery3d','com.coloros.calculator','com.coloros.smartsidebar','com.coloros.assistantscreen'}
manifest=(root/'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
assert 'android.permission.INTERNET' not in manifest
assert 'legacyVolumeSupported()' in hook and 'VolumeMaskGuard.windowRoots()' in hook
assert 'HardwareBackdropBlur' in (root/'app/src/main/kotlin/com/coloros16/liquidglass/hook/ButtonBackdropWorker.kt').read_text(encoding='utf-8')
assert 'ClassValue' not in (root/'app/src/main/kotlin/com/coloros16/liquidglass/hook/BlurDrawHook.kt').read_text(encoding='utf-8')

report = {
    'product': 'DreamGlass', 'supportedRelease': 'For ColorOS 17', 'version': version,
    'versionCode': version_code, 'package': 'com.dreamglass.cos17', 'debuggable': False,
    'apkSha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(),
    'runtimeComponents': len(inventory['components']),
    'retainedNoticeEntries': len(inventory['retainedOriginalNotices']),
    'unitTestsPassed': unit_count, 'lintErrors': 0,
    'lintWarnings': sum(n.get('severity') == 'Warning' for n in lint),
    'parameterBindingLayoutsPreserved': layout_count,
    'opticalBaseline': '1.0.7 fixture', 'parameterBindingBaseline': baseline, 'opticalChangesLimitedToMarkedLensAndCoat': True, 'materialAlphaControlRemoved': True, 'snapshotBindingFirstDrawAndRefreshChecked': True,
    'upstreamBase': '1bb018615af3261d3e9de0165169a0e75917d2fe',
    'particlePullRequest': 'https://github.com/AYwlilwYA/ColorOS16-LiquidGlass/pull/2',
    'hardwareBackendPackageChecked': True, 'keyguardGlyphAndBackdropPackaged': True, 'hostScopes': sorted(scopes),
    'physicalDeviceAccessed': False, 'colorOsRuntimeTested': False,
}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2))
