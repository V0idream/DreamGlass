"""Render shipping AGSL on complete procedural backdrops. This is not an iOS/ColorOS screenshot.

python scripts/render_material_study.py --skia-path ../../work/skia-validation --output <dir>
The baseline, backdrops, sample rects and uniforms are fixed so visual iteration is comparable.
"""
import argparse, hashlib, json, math, re, sys
from pathlib import Path
p=argparse.ArgumentParser()
p.add_argument('--skia-path',required=True);p.add_argument('--output',required=True)
p.add_argument('--profile',choices=['current','ios'],default='current')
p.add_argument('--label',default='candidate');p.add_argument('--quick',action='store_true')
args=p.parse_args();sys.path.insert(0,str(Path(args.skia_path).resolve()))
import skia
import numpy as np
from PIL import Image,ImageDraw,ImageFilter
root=Path(__file__).resolve().parents[1];out=Path(args.output);out.mkdir(parents=True,exist_ok=True)
text=(root/'app/src/main/kotlin/com/coloros16/liquidglass/liquidglass/LiquidGlassShader.kt').read_text(encoding='utf-8')
source=re.search(r'val AGSL_SOURCE: String = """(.*?)""".trimIndent\(\)',text,re.S)[1]
baseline=(root/'docs/validation/1.0.8/1.0.7-baseline.agsl').read_text(encoding='utf-8')
base=lambda s:re.sub(r'// @LG-MB-START[^\n]*\n.*?// @LG-MB-END[^\n]*\n','',s,flags=re.S)
effects={'1.0.7':skia.RuntimeEffect.MakeForShader(base(baseline)),args.label:skia.RuntimeEffect.MakeForShader(base(source))}
full=skia.RuntimeEffect.MakeForShader(source)
W,H=560,970
def backdrop(mode):
 y,x=np.mgrid[:H,:W];x=x/W;y=y/H
 if mode=='dark':
  a=np.stack([18+25*y+32*np.exp(-((x-.2)**2+(y-.6)**2)*14),
              24+46*y+40*np.exp(-((x-.7)**2+(y-.2)**2)*12),
              48+60*y+32*np.sin(x*3+y*2)**2],axis=2)
 elif mode=='light':
  a=np.stack([225-24*y+17*x,220-8*x-40*y,210+20*y+18*x],axis=2)
 else:
  a=np.stack([110+85*np.sin(x*4+y*3),100+80*np.sin(x*6-y*4+2),110+90*np.cos(x*5+y*4)],axis=2)
 image=Image.fromarray(np.uint8(np.clip(a,0,255)),'RGB');d=ImageDraw.Draw(image)
 for i in range(6):
  cy=90+i*152
  fill=(43,65,98) if mode=='dark' else ((248,245,230) if mode=='light' else (50+i*25,80,155))
  d.rounded_rectangle((25,cy,535,cy+118),22,fill=fill)
  for j in range(3):d.text((46,cy+20+j*28),'DreamGlass  /  foreground + background  012345',fill=(168,184,205) if mode=='dark' else (55,61,72))
 for i in range(5):
  cx=30+i*115;d.rounded_rectangle((cx,H-96,cx+75,H-21),18,fill=[(44,175,235),(238,93,126),(150,170,33),(82,184,142),(168,92,222)][i])
 return image.convert('RGBA')
def render(effect,image,rect,corner,polish=1,blur=13,highlight=.6,depth=2.4,angle=43,conic=False,amount=64,vibrancy=1.5,mask=.10,highlight_width=8,height_ratio=.5,falloff=3,floor=.16,inner_att=0):
 x,y,w,h=rect;builder=skia.RuntimeEffectBuilder(effect)
 # Fixture preprocessing only. Actual production F16 filtering is separately tested on Android.
 pre=image.filter(ImageFilter.GaussianBlur(blur*.48)) if blur else image
 im=skia.Image.fromarray(np.asarray(pre),colorType=skia.ColorType.kRGBA_8888_ColorType)
 child=im.makeShader(skia.TileMode.kClamp,skia.TileMode.kClamp,skia.SamplingOptions(skia.FilterMode.kLinear))
 builder.setChild('uSource',child);builder.setChild('uSourceLowRes',child)
 u={'uSourceRect':[x,y,w,h],'uSourceLowResRect':[x,y,w,h],'uViewport':[w,h],
    'uCornerRadius':corner,'uCornerWeight':1.3968,'uCornerIsConic':int(conic),'uCornerConicBlend':1,
    'uBlurRadius':blur,'uSourcePreblurred':int(blur>0),'uSurfacePolish':polish,
    'uRefractionHeight':min(w,h)*height_ratio,'uRefractionAmount':amount,'uDepth':depth,
    'uDispersion':0,'uHighlight':highlight,'uHighlightWidth':highlight_width,'uHighlightFalloff':falloff,
    'uHighlightFloor':floor,'uHighlightInnerAtt':inner_att,'uInnerGlow':0,'uVibrancy':vibrancy,
    'uLightAngle':math.radians(angle),'uParallax':[0,0],'uMaskRect':[0,0,w,h],
    'uMaskCornerRadius':0,'uMaskWeight':1.3968,'uMaskOffset':[0,0],'uMaskAlpha':mask,
    'uMaskColor':[0,0,0,1],'uTilt':[0,1,800,0]}
 for name,v in u.items():builder.setUniform(name,v if isinstance(v,list) else float(v))
 surf=skia.Surface.MakeRaster(skia.ImageInfo.Make(w,h,skia.ColorType.kRGBA_F32_ColorType,skia.AlphaType.kPremul_AlphaType))
 c=surf.getCanvas();c.clear(skia.ColorTRANSPARENT);c.drawRect(skia.Rect.MakeWH(w,h),skia.Paint(Shader=builder.makeShader()))
 snap=surf.makeImageSnapshot();px=snap.toarray(colorType=skia.ColorType.kRGBA_F32_ColorType,alphaType=skia.AlphaType.kPremul_AlphaType)
 assert np.isfinite(px).all()
 if polish:
  assert np.max(px[...,:3]-px[...,3:])<2e-5
  assert np.max(np.abs(px[px[...,3]==0,:3]),initial=0)==0
 return Image.fromarray(snap.toarray(colorType=skia.ColorType.kRGBA_8888_ColorType)),px
shapes=[(32,64,238,102,51),(290,64,238,102,51),
        (32,185,238,102,51),(290,185,102,102,51),(426,185,102,102,51),
        (32,312,102,235,51),(168,312,102,235,51),(290,312,238,235,56)]
for row in range(3):
 for col in range(4):shapes.append((32+col*132,580+row*125,102,102,51))
preset_source=(root/'app/src/main/kotlin/com/coloros16/liquidglass/glass/IosGlassPreset.kt').read_text(encoding='utf-8')
const_source=(root/'app/src/main/kotlin/com/coloros16/liquidglass/config/Prefs.kt').read_text(encoding='utf-8')
keys=dict(re.findall(r'const val (KEY_[A-Z_]+) = "([^"]+)"',const_source))
preset={keys[k]:float(v) for k,v in re.findall(r'Prefs.(KEY_[A-Z_]+) to ([0-9.]+)f',preset_source)}
style=dict(blur=preset['blur_radius'],highlight=preset['highlight'],depth=preset['depth'],
           angle=preset['light_angle'],amount=preset['refraction_amount'],vibrancy=preset['vibrancy'],
           falloff=preset['highlight_falloff'],floor=preset['highlight_floor'],inner_att=preset['highlight_inner_att'],
           mask=preset['mask_glass_dim'],highlight_width=preset['highlight_width'],height_ratio=preset['refraction_height_ratio'])
def panel(effect,bg,options):
 image=bg.copy()
 for i,(x,y,w,h,r) in enumerate(shapes):
  glass,_=render(effect,bg,(x,y,w,h),r,**options)
  image.alpha_composite(glass,(x,y));d=ImageDraw.Draw(image)
  pixel=np.asarray(bg)[y+h//2,x+w//2,:3];ink=(25,31,40) if float(np.dot(pixel,[.213,.715,.072]))>170 else (232,239,247)
  secondary=(65,73,85) if ink==(25,31,40) else (199,211,225)
  if i<3:
   d.arc((x+23,y+32,x+49,y+58),0,300,fill=(38,145,255),width=4)
   d.text((x+70,y+28),['NETWORK','DREAM VPN','BLUETOOTH'][i],fill=ink);d.text((x+70,y+52),'Connected',fill=secondary)
  elif i in (5,6):
   d.ellipse((x+40,y+h-57,x+62,y+h-35),fill=(255,210,50) if i==5 else (42,150,255))
  elif i==7:
   d.text((x+28,y+38),'DreamGlass',fill=ink);d.text((x+28,y+60),'Material study',fill=secondary)
   d.polygon([(x+109,y+h-55),(x+109,y+h-27),(x+132,y+h-41)],fill=ink)
  else:
   cx=x+w//2;cy=y+h//2
   d.ellipse((cx-13,cy-13,cx+13,cy+13),outline=ink,width=3)
   d.line((cx-21,cy,cx+21,cy),fill=ink,width=3)
 d=ImageDraw.Draw(image);d.text((32,22),'PROCEDURAL STUDY - SHIPPING AGSL - NOT A PHONE SCREENSHOT',fill=(205,215,230))
 return image
modes=['dark'] if args.quick else ['dark','light','color']
for mode in modes:
 bg=backdrop(mode);sheet=Image.new('RGB',(W*2+32,H+52),(11,17,25));d=ImageDraw.Draw(sheet)
 for col,(name,effect) in enumerate(effects.items()):
  image=panel(effect,bg,style if name==args.label and args.profile=="ios" else {});image.save(out/f'{mode}-{name}.png')
  d.text((col*(W+32)+16,15),name,fill='white');sheet.paste(image,(col*(W+32),42))
 sheet.save(out/f'{mode}-comparison.png')
# Geometry/coverage and unchanged non-polished scenes are checked on actual source variants.
checks=[]
for name,rect,r in [('round',(50,80,160,160),80),('wide',(30,50,360,150),75),('card',(40,60,330,250),48)]:
 bg=backdrop('color')
 _,old=render(effects['1.0.7'],bg,rect,r);_,new=render(effects[args.label],bg,rect,r)
 assert np.max(np.abs(old[...,3]-new[...,3]))<2e-6
 _,disabled_old=render(effects['1.0.7'],bg,rect,r,polish=0)
 _,disabled_new=render(effects[args.label],bg,rect,r,polish=0)
 assert np.max(np.abs(disabled_old-disabled_new))<2e-6
 _,full_px=render(full,bg,rect,r)
 assert np.max(np.abs(full_px-new))<2e-6
 checks.append({'shape':name,'coverageUnchanged':True,'unpolishedUnchanged':True,'baseFullEqual':True})

# The source itself encodes screen Y. With all decorative light disabled, sampled G is the
# actual refracted coordinate; a descending run proves an optical fold rather than a border.
yy,xx=np.mgrid[:H,:W];gradient=np.empty((H,W,4),dtype=np.uint8)
gradient[...,0]=(xx*255/(W-1)).astype(np.uint8)
gradient[...,1]=(yy*255/(H-1)).astype(np.uint8);gradient[...,2]=90;gradient[...,3]=255
gradient=Image.fromarray(gradient)
fold_checks=[]
for amount in [0,16,64,200,1000]:
 _,px=render(effects[args.label],gradient,(30,50,360,150),75,blur=0,highlight=0,
             amount=amount,vibrancy=1,mask=0)
 difference=np.diff(px[3:-3,180,1]);minimum=float(difference.min())
 assert minimum>=-2e-6,(amount,'folded edge coordinate',minimum)
 fold_checks.append({'amount':amount,'minCoordinateStep':minimum})
for shape,rect,r in [('circle',(20,40,160,160),80),('wide',(30,50,360,150),75)]:
 for conic in [False,True]:
  for blur in [0,13,64]:
   for angle in [0,43,180]:render(effects[args.label],backdrop('light'),rect,r,conic=conic,blur=blur,angle=angle)
 _,off=render(effects[args.label],backdrop('color'),rect,r,highlight=0,amount=0)
 _,old_off=render(effects['1.0.7'],backdrop('color'),rect,r,highlight=0,amount=0)
 assert np.max(np.abs(off-old_off))<2e-6,(shape,'disabled optics changed')
 _,no_width=render(effects[args.label],backdrop('color'),rect,r,highlight_width=0,amount=0)
 assert np.max(np.abs(off-no_width))<2e-6,(shape,'width zero emitted light')

report={'monotoneLensChecks':fold_checks,'controlAndConicMatrixPassed':True,'profile':preset if args.profile=='ios' else None,'label':args.label,'sourceSha256':hashlib.sha256(source.encode()).hexdigest(),
 'backdrops':modes,'checks':checks,'shippingAgslRendered':True,'androidGpuTested':False,
 'productionCpuFilterUsed':False,'note':'Pillow blur is a fixture input only; Android verifies the production filter separately.'}
(out/'study.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
