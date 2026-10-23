"""Compile/render the actual shipping shader with desktop Skia. This is not Android GPU proof.

Dependencies: skia-python, numpy, Pillow. All images are procedural test fixtures.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import sys

p = argparse.ArgumentParser()
p.add_argument('--skia-path')
p.add_argument('--output', required=True)
p.add_argument('--baseline', default='7d2208598be20ab8690894b9facdf83b9a74dec1')
args = p.parse_args()
if args.skia_path:
    sys.path.insert(0, str(Path(args.skia_path).resolve()))
import skia
import numpy as np
from PIL import Image, ImageDraw

repo = Path(__file__).resolve().parents[1]
relative = 'app/src/main/kotlin/com/coloros16/liquidglass/liquidglass/LiquidGlassShader.kt'
text = (repo / relative).read_text(encoding='utf-8')


def extract(source):
    return re.search(r'val AGSL_SOURCE: String = """(.*?)""".trimIndent\(\)', source, re.S)[1]


def base(source):
    return re.sub(r'// @LG-MB-START[^\n]*\n.*?// @LG-MB-END[^\n]*\n', '', source, flags=re.S)


source = extract(text)
baseline_fixture = repo / 'docs/validation/refine4/refine3-baseline.agsl'
if baseline_fixture.exists():
    prior = baseline_fixture.read_text(encoding='utf-8')
else:
    old = subprocess.check_output(['git', '-C', str(repo), 'show', args.baseline + ':' + relative]).decode('utf-8')
    prior = extract(old)
effects = {name: skia.RuntimeEffect.MakeForShader(code) for name, code in {
    'full': source, 'base': base(source), 'prior-full': prior, 'prior-base': base(prior)
}.items()}
out = Path(args.output)
out.mkdir(parents=True, exist_ok=True)


def backdrop(w, h, flat=False):
    if flat:
        array = np.empty((h, w, 4), dtype=np.uint8)
        array[:] = [24, 32, 58, 255]
    else:
        yy, xx = np.mgrid[:h, :w]
        array = np.zeros((h, w, 4), dtype=np.uint8)
        for c, phase in enumerate([0.0, 2.3, 4.5]):
            channel = 85 + 60 * np.sin(xx / 42 + phase) + 42 * np.sin(yy / 61 + phase)
            array[:, :, c] = np.clip(channel, 8, 235)
        array[:, :, 3] = 255
        # Fine detail distinguishes retained background structure from diffuse bevel shading.
        image = Image.fromarray(array)
        draw = ImageDraw.Draw(image)
        for y in range(22, h, 46):
            draw.text((18, y), 'DreamGlass  012345  clear light', fill=(235, 240, 250, 255))
        array = np.array(image)
    return skia.Image.fromarray(array, colorType=skia.ColorType.kRGBA_8888_ColorType)


def render(effect, w, h, radius, polish=0, blur=8, flat=False, highlight=.6, opacity=1):
    builder = skia.RuntimeEffectBuilder(effect)
    image = backdrop(w, h, flat)
    image_shader = image.makeShader(skia.TileMode.kClamp, skia.TileMode.kClamp,
                                    skia.SamplingOptions(skia.FilterMode.kLinear))
    builder.setChild('uSource', image_shader)
    builder.setChild('uSourceLowRes', image_shader)
    uniforms = {
        'uSourceRect': [0, 0, w, h], 'uSourceLowResRect': [0, 0, w, h],
        'uViewport': [w, h], 'uCornerRadius': radius, 'uCornerWeight': 1.3968,
        'uCornerIsConic': 0.0, 'uCornerConicBlend': 0.0, 'uBlurRadius': blur,
        'uRefractionHeight': min(w, h) * .5, 'uRefractionAmount': 64.0,
        'uDepth': 2.4, 'uDispersion': .22, 'uHighlight': highlight,
        'uHighlightWidth': 8.0, 'uHighlightFalloff': 3.0, 'uHighlightFloor': .16,
        'uHighlightInnerAtt': 0.0, 'uInnerGlow': 0.0, 'uVibrancy': 1.5,
        'uLightAngle': math.radians(43), 'uParallax': [0, 0],
        'uMaskRect': [0, 0, w, h], 'uMaskCornerRadius': 0.0,
        'uMaskWeight': 1.3968, 'uMaskOffset': [0, 0], 'uMaskAlpha': .10,
        'uMaskColor': [0, 0, 0, 1], 'uTilt': [0, 1, 800, 0],
    }
    if 'uSurfacePolish' in [u.name for u in effect.uniforms()]:
        uniforms['uSurfacePolish'] = float(polish)
    if 'uMaterialOpacity' in [u.name for u in effect.uniforms()]:
        uniforms['uMaterialOpacity'] = float(opacity)
    for name, value in uniforms.items():
        builder.setUniform(name, value if isinstance(value, list) else float(value))
    surface = skia.Surface.MakeRaster(skia.ImageInfo.Make(w, h,
        skia.ColorType.kRGBA_F32_ColorType, skia.AlphaType.kPremul_AlphaType))
    canvas = surface.getCanvas()
    canvas.clear(skia.ColorTRANSPARENT)
    canvas.drawRect(skia.Rect.MakeWH(w, h), skia.Paint(Shader=builder.makeShader()))
    snapshot = surface.makeImageSnapshot()
    pixels = snapshot.toarray(colorType=skia.ColorType.kRGBA_F32_ColorType,
                             alphaType=skia.AlphaType.kPremul_AlphaType)
    display = snapshot.toarray(colorType=skia.ColorType.kRGBA_8888_ColorType)
    return pixels, Image.fromarray(display)


cases = [('round tile', 160, 160, 80), ('wide tile', 360, 160, 80),
         ('slider', 160, 350, 80), ('media', 360, 310, 64),
         ('notification', 620, 160, 38)]
results = []
sheet = Image.new('RGB', (1360, 1550), (12, 17, 32))
draw = ImageDraw.Draw(sheet)
draw.text((24, 18), 'OFFLINE SKIA FIXTURE - no Android / no phone screenshot', fill='white')
draw.text((24, 45), 'Refine3', fill='white')
draw.text((700, 45), 'Refine4: fine rim / clearer backdrop', fill='white')
y = 76
for name, w, h, radius in cases:
    before, before_image = render(effects['prior-base'], w, h, radius)
    unchanged, _ = render(effects['base'], w, h, radius)
    regression = float(np.max(np.abs(before - unchanged)))
    assert regression < 2e-6, (name, 'unselected scene changed', regression)
    after, after_image = render(effects['base'], w, h, radius, polish=1)
    full, _ = render(effects['full'], w, h, radius, polish=1)
    assert np.max(np.abs(full - after)) < 2e-6, (name, 'BASE/FULL differ without metaballs')
    for blur in [0, 8, 30]:
        for highlight in [.6, 2.0]:
            px, _ = render(effects['base'], w, h, radius, polish=1, blur=blur, highlight=highlight)
            assert np.all(np.isfinite(px)), (name, 'non-finite output')
            assert np.max(px[:, :, :3] - px[:, :, 3:]) <= 2e-6, (name, 'RGB exceeds alpha')
            outside = px[:, :, 3] == 0
            assert np.max(px[outside, :3], initial=0) == 0, (name, 'light outside shape')
    results.append({'shape': name, 'nonPolishedMaxDelta': regression,
                    'finiteAndPremultiplied': True, 'zeroLightOutsideShape': True})
    draw.text((24, y), name, fill=(185, 196, 220))
    sheet.paste(before_image, (24, y + 22), before_image)
    sheet.paste(after_image, (700, y + 22), after_image)
    y += h + 52
sheet = sheet.crop((0, 0, 1360, y + 12))
sheet.save(out / 'surface-comparison.png')

# Measure edge energy on a flat source: the finish must narrow, rather than merely dim, the rim.
old_rim, _ = render(effects['prior-base'], 360, 160, 80, flat=True)
new_rim, _ = render(effects['base'], 360, 160, 80, polish=1, flat=True)
def rim_width(px):
    luminance = px[:24, 180, :3].mean(axis=1)
    base_level = px[50, 180, :3].mean()
    reflection = np.maximum(luminance - base_level, 0)
    return int(np.count_nonzero(reflection > reflection.max() * .3))
old_width, new_width = rim_width(old_rim), rim_width(new_rim)
assert 0 < new_width < old_width, (old_width, new_width)

# Ordinary round-corner clipping intersects the *entire* slider footprint with the native progress
# rectangle; test 0/1/25/50/99/100%. OEM smooth-corner construction is separately checked in DEX.
progress_rows = []
for progress in [0, .01, .25, .5, .99, 1.0]:
    surface = skia.Surface(160, 350)
    canvas = surface.getCanvas()
    canvas.clear(skia.ColorTRANSPARENT)
    footprint = skia.Path()
    footprint.addRoundRect(skia.Rect.MakeWH(160, 350), 80, 80)
    saved = canvas.save()
    canvas.clipPath(footprint, doAntiAlias=True)
    canvas.clipRect(skia.Rect.MakeLTRB(0, 350 * (1 - progress), 160, 350))
    canvas.drawColor(skia.ColorWHITE)
    canvas.restoreToCount(saved)
    px = surface.makeImageSnapshot().toarray(colorType=skia.ColorType.kRGBA_8888_ColorType)
    assert px[-1, 0, 3] == 0 and px[-1, -1, 3] == 0, ('square bottom corner', progress)
    if progress == 0: assert px[:, :, 3].max() == 0
    if progress > 0: assert px[-2, 80, 3] > 0
    progress_rows.append(Image.fromarray(px))
progress_sheet = Image.new('RGBA', (6 * 180, 390), (12, 17, 32, 255))
labels = ImageDraw.Draw(progress_sheet)
for i, (image, value) in enumerate(zip(progress_rows, [0, 1, 25, 50, 99, 100])):
    labels.text((i * 180 + 10, 10), str(value) + '%', fill='white')
    progress_sheet.alpha_composite(image, (i * 180 + 10, 35))
progress_sheet.save(out / 'slider-clip-fixture.png')
opacity_checks = []
for name in ('full', 'base'):
    full, _ = render(effects[name], 360, 160, 80, polish=1, opacity=1)
    for opacity in (0, .25, .5, .75, 1):
        pixels, _ = render(effects[name], 360, 160, 80, polish=1, opacity=opacity)
        error = float(np.max(np.abs(pixels - full * opacity)))
        assert error < .00001, (name, opacity, error)
        assert np.all(pixels[..., :3] <= pixels[..., 3:4] + .00001)
        opacity_checks.append({'variant': name, 'opacity': opacity, 'maxPremultipliedScaleError': error})

report = {'engine': 'desktop Skia ' + skia.__version__, 'androidGpuTested': False,
          'shaderSourceSha256': hashlib.sha256(source.encode()).hexdigest(),
          'compiledVariants': list(effects), 'cases': results,
          'rimWidthAt30Percent': {'beforePx': old_width, 'afterPx': new_width},
          'progressClippingPercentages': [0, 1, 25, 50, 99, 100], 'opacityChecks': opacity_checks}
(out / 'shader-validation.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
print(json.dumps(report, indent=2))
