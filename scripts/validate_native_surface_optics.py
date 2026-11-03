"""Offline Skia reference for AppGlassEffect host-local optics. Synthetic pixels only.

This is NOT Android/OEM backdrop proof. skia-python exposes no RuntimeShader
ImageFilter factory, so external blur is rasterized to a whole-host F32 image
before the actual shared shader samples it. Android filter bounds/origin behavior
and native outline clipping remain untested by this reference.
"""
from pathlib import Path
import hashlib
import json
import math
import re
import sys
import argparse

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--module-dir',type=Path)
args=parser.parse_args()
if args.module_dir: sys.path.insert(0,str(args.module_dir))
import skia
import numpy as np
from PIL import Image, ImageDraw

OUT = args.output
OUT.mkdir(parents=True,exist_ok=True)
SOURCE_PATH = REPO / 'app/src/main/kotlin/com/coloros16/liquidglass/liquidglass/LiquidGlassShader.kt'
SOURCE = re.search(r'val AGSL_SOURCE: String = """(.*?)""".trimIndent\(\)',
                   SOURCE_PATH.read_text(encoding='utf-8'), re.S)[1]
EFFECT = skia.RuntimeEffect.MakeForShader(SOURCE)
NAMES = {u.name for u in EFFECT.uniforms()}
W, H = 440, 260
BOUNDS = (73, 41, 367, 219)
INFO = skia.ImageInfo.Make(W, H, skia.ColorType.kRGBA_F32_ColorType, skia.AlphaType.kPremul_AlphaType)


def procedure():
    y, x = np.mgrid[:H, :W]
    pixels = np.empty((H, W, 4), np.uint8)
    pixels[..., 0] = np.clip(30 + 180 * x / W + 22 * ((x // 19 + y // 19) % 2), 0, 255)
    pixels[..., 1] = np.clip(20 + 185 * y / H, 0, 255)
    pixels[..., 2] = np.clip(48 + 100 * (.5 + .5 * np.sin((x + y) / 11)), 0, 255)
    pixels[..., 3] = 255
    return skia.Image.fromarray(pixels, colorType=skia.ColorType.kRGBA_8888_ColorType)


SOURCE_IMAGE = procedure()


def raster_preblur(sigma):
    if not sigma:
        return SOURCE_IMAGE
    surface = skia.Surface.MakeRaster(INFO)
    canvas = surface.getCanvas()
    canvas.clear(skia.ColorTRANSPARENT)
    paint = skia.Paint()
    paint.setImageFilter(skia.ImageFilters.Blur(sigma, sigma, skia.TileMode.kClamp))
    canvas.drawImage(SOURCE_IMAGE, 0, 0, skia.SamplingOptions(), paint)
    return surface.makeImageSnapshot()


def uniforms(bounds, parallax, blur=0, identity=False):
    l, t, r, b = bounds
    width, height = r-l, b-t
    values = {
        'uSourceRect': [l, t, width, height], 'uSourceLowResRect': [l, t, width, height],
        'uViewport': [width, height], 'uCornerRadius': 44., 'uCornerWeight': 1.3968,
        'uCornerIsConic': 0., 'uCornerConicBlend': 1., 'uBlurRadius': float(blur),
        'uSourcePreblurred': 1., 'uRefractionHeight': height*.5,
        'uRefractionAmount': 0. if identity else 64., 'uDepth': 0. if identity else .72,
        'uDispersion': 0. if identity else .3, 'uHighlight': 0. if identity else .56,
        'uHighlightWidth': 8., 'uHighlightFalloff': 4., 'uHighlightFloor': .16,
        'uHighlightInnerAtt': 0., 'uInnerGlow': 0., 'uVibrancy': 1. if identity else 1.54,
        'uLightAngle': math.radians(51), 'uParallax': list(parallax),
        'uSurfacePolish': 0. if identity else 1., 'uCompactOptics': 0., 'uGrayBase': [0., 0.],
        'uDebugCoord': 0., 'uDebugHighlight': 0., 'uDebugGlassBounds': 0.,
        'uMaskRect': [l, t, width, height], 'uMaskCornerRadius': 0.,
        'uMaskWeight': 1.3968, 'uMaskOffset': [l, t], 'uMaskAlpha': 0.,
        'uMaskColor': [0., 0., 0., 1.], 'uTilt': [0., 0., 0., 0.],
        'uMetaballEnable': 0., 'uBodyRect': [0.]*4, 'uBall1': [0.]*4,
        'uBall2': [0.]*4, 'uBall3': [0.]*4, 'uMetaballParam': [0.]*4,
    }
    missing = NAMES-values.keys()
    assert not missing, f'New uniforms must be explicitly handled: {missing}'
    return values


def render(bounds=BOUNDS, parallax=(0, 0), blur=0, identity=False):
    builder = skia.RuntimeEffectBuilder(EFFECT)
    image = raster_preblur(blur)
    child = image.makeShader(skia.TileMode.kClamp, skia.TileMode.kClamp,
                             skia.SamplingOptions(skia.FilterMode.kLinear))
    builder.setChild('uSource', child)
    builder.setChild('uSourceLowRes', child)
    for name, value in uniforms(bounds, parallax, blur, identity).items():
        if name in NAMES:
            builder.setUniform(name, value)
    surface = skia.Surface.MakeRaster(INFO)
    canvas = surface.getCanvas()
    canvas.clear(skia.ColorTRANSPARENT)
    canvas.drawRect(skia.Rect.MakeWH(W, H), skia.Paint(Shader=builder.makeShader()))
    snapshot = surface.makeImageSnapshot()
    pixels = snapshot.toarray(colorType=skia.ColorType.kRGBA_F32_ColorType,
                             alphaType=skia.AlphaType.kPremul_AlphaType)
    image = Image.fromarray(snapshot.toarray(colorType=skia.ColorType.kRGBA_8888_ColorType))
    return pixels, image


def metrics(pixels):
    a = pixels[..., 3]
    yy, xx = np.mgrid[:H, :W]
    occupied = a > .01
    ys, xs = np.nonzero(occupied)
    bbox = [int(xs.min()), int(ys.min()), int(xs.max()+1), int(ys.max()+1)] if xs.size else None
    l, t, r, b = BOUNDS
    outside = (xx < l) | (xx >= r) | (yy < t) | (yy >= b)
    mass = float(a.sum())
    return {
        'alphaBboxAt01': bbox,
        'alphaMass': mass,
        'alphaCentroidPx': [float(((xx+.5)*a).sum()/mass), float(((yy+.5)*a).sum()/mass)],
        'finite': bool(np.all(np.isfinite(pixels))),
        'maximumRgbMinusAlpha': float(np.max(pixels[..., :3]-a[..., None])),
        'maximumAlphaOutsideMaskRectangle': float(a[outside].max(initial=0)),
        'maximumAbsRgbAtZeroAlpha': float(np.abs(pixels[a == 0, :3]).max(initial=0)),
    }


def save_display(image, name):
    background = Image.fromarray(SOURCE_IMAGE.toarray(colorType=skia.ColorType.kRGBA_8888_ColorType)).convert('RGBA')
    background.alpha_composite(image)
    draw = ImageDraw.Draw(background)
    draw.rectangle(BOUNDS, outline=(255, 20, 30), width=1)
    background.save(OUT / (name+'.png'))


records = []
all_pixels = {}
parallaxes = [(0, 0), (24, -16), (-24, 16), (64, 48)]
sheet = Image.new('RGB', (W*4, (H+32)*2), (12, 15, 20))
labels = ImageDraw.Draw(sheet)
for row, blur in enumerate([0, 20]):
    for col, parallax in enumerate(parallaxes):
        pixels, image = render(parallax=parallax, blur=blur)
        key = f'blur{blur}-parallax{parallax[0]}_{parallax[1]}'
        all_pixels[(blur, parallax)] = pixels
        result = metrics(pixels)
        result.update({'case': key, 'parallaxPx': parallax, 'separateSourcePreblurSigma': blur,
                       'nativeMaskRect': BOUNDS,
                       'unclippedBodyCenterFromEquation': [(BOUNDS[0]+BOUNDS[2])*.5-parallax[0],
                                                          (BOUNDS[1]+BOUNDS[3])*.5-parallax[1]]})
        records.append(result)
        save_display(image, key)
        alpha = Image.fromarray(np.uint8(np.clip(pixels[..., 3], 0, 1)*255)).convert('RGB')
        alpha_draw = ImageDraw.Draw(alpha)
        alpha_draw.rectangle(BOUNDS, outline='red')
        sheet.paste(alpha, (col*W, row*(H+32)+32))
        labels.text((col*W+10, row*(H+32)+10), key, fill='white')

sheet.save(OUT/'alpha-geometry-comparison.png')
# Before/after adapter comparison, same actual AGSL and synthetic source.
comparison=Image.new('RGB',(W*2,(H+28)*2),(12,15,20))
labels2=ImageDraw.Draw(comparison)
for row,blur in enumerate([0,20]):
 for col,px in enumerate([(24,-16),(0,0)]):
  _,im=render(parallax=px,blur=blur)
  bg=Image.fromarray(SOURCE_IMAGE.toarray(colorType=skia.ColorType.kRGBA_8888_ColorType)).convert('RGBA')
  bg.alpha_composite(im);comparison.paste(bg,(col*W,row*(H+28)+28))
  labels2.text((col*W+6,row*(H+28)+6),('Before: displaced mask' if col==0 else 'After: anchored mask')+f', sigma={blur}',fill='white')
comparison.save(OUT/'native-mask-before-after.png')
coverage_checks = []
for parallax in parallaxes:
    maximum = float(np.max(np.abs(all_pixels[(0, parallax)][..., 3]-all_pixels[(20, parallax)][..., 3])))
    coverage_checks.append({'parallaxPx': parallax, 'maxAlphaDeltaPreblur0Versus20': maximum})
    assert maximum < 2e-5, ('Preblur reference unexpectedly moved coverage', maximum)

baseline = all_pixels[(0, (0, 0))]
for record in records:
    assert record['finite'] and record['maximumRgbMinusAlpha'] < 2e-5, record
    assert record['maximumAlphaOutsideMaskRectangle'] < 2e-5, record
    assert record['maximumAbsRgbAtZeroAlpha'] < 2e-5, record

# Validate an integer-origin translation of actual shader alpha, without relying
# on the blurred image-filter chain or the vendor background coordinate domain.
local_pixels, _ = render(bounds=(0, 0, BOUNDS[2]-BOUNDS[0], BOUNDS[3]-BOUNDS[1]))
l, t, r, b = BOUNDS
translation_error = float(np.max(np.abs(baseline[t:b, l:r, 3]-local_pixels[:b-t, :r-l, 3])))
assert translation_error < 2e-5

# No refraction/light/color changes: the source-coordinate equation should sample
# the same whole-host pixel at arbitrary nonzero material origin.
identity, _ = render(identity=True)
original = SOURCE_IMAGE.toarray(colorType=skia.ColorType.kRGBA_F32_ColorType,
                               alphaType=skia.AlphaType.kPremul_AlphaType)
points = [(160, 110), (200, 130), (250, 155), (300, 110)]
source_checks = [{'windowPixelXY': [x, y], 'maxRgbaDelta': float(np.max(np.abs(identity[y, x]-original[y, x])))}
                 for x, y in points]
assert max(x['maxRgbaDelta'] for x in source_checks) < .002

# Proposed adapter-only correction: parallax is geometric in the shared shader.
# Native app controls have fixed outlines; pass zero only from AppGlassEffect,
# retaining the common shader and SystemUI/QS's existing optical behavior.
proposed, proposed_image = render(parallax=(0, 0), blur=20)
save_display(proposed_image, 'proposed-native-anchored-parallax-zero')
assert np.max(np.abs(proposed-all_pixels[(20,(0,0))])) < 2e-5
displacement = []
base_mass = metrics(baseline)['alphaMass']
for parallax in parallaxes[1:]:
    p = all_pixels[(0, parallax)]
    displacement.append({'parallaxPx': parallax,
                         'meanAbsAlphaDeltaFromAnchored': float(np.mean(np.abs(p[..., 3]-baseline[..., 3]))),
                         'alphaMassRatioFromAnchored': metrics(p)['alphaMass']/base_mass,
                         'maximumAlphaDeltaFromAnchored': float(np.max(np.abs(p[..., 3]-baseline[..., 3])))})

report = {
    'scope': 'OFFLINE desktop Skia raster/F32 reference using actual shared AGSL. Synthetic procedural source only.',
    'skiaVersion': skia.__version__, 'androidGpuTested': False, 'oemBackdropTested': False,
    'fullAndroidFilterChainTested': False,
    'runtimeShaderImageFilterExposed': hasattr(skia.ImageFilters, 'RuntimeShader'),
    'blurReferenceLimit': 'Gaussian ImageFilter is rasterized first to a fixed whole-host F32 image. Runtime shader samples that image; Android filter expansion/origin metadata is NOT reproduced.',
    'sourceFile': str(SOURCE_PATH.relative_to(REPO)), 'shaderSha256': hashlib.sha256(SOURCE.encode()).hexdigest(),
    'hostPixels': [W, H], 'nativeBoundsLTRB': BOUNDS, 'actualShaderCases': records,
    'nonzeroOriginAlphaTranslationMaxError': translation_error,
    'sourceCoordinateIdentitySamples': source_checks, 'preblurCoverageChecks': coverage_checks,
    'parallaxCoverageChanges': displacement,
    'equation': 'centeredCoord=(coord+uParallax-uMaskOffset)-uViewport/2, so unmasked shape center=maskOffset+viewport/2-parallax; fixed uMaskRect then clips the displaced shape.',
    'recommendation': 'AppGlassEffect adapter only: pass params.copy(parallaxX=0f,parallaxY=0f) for native fixed-shape controls. Preserve maskOffset/sourceRect; do not alter shared AGSL or SystemUI/QS uniforms. Native helper must still enforce actual Outline/rounded clip. Android full-filter-chain bounds remain a separate unverified hypothesis.',
    'status': 'PASS_OFFLINE_SHADER_INVARIANTS_PARALLAX_GEOMETRY_DISPLACEMENT_REPRODUCED',
}
(OUT/'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'status': report['status'], 'translationError': translation_error,
                  'parallaxCoverageChanges': displacement, 'output': str(OUT)}, indent=2))
