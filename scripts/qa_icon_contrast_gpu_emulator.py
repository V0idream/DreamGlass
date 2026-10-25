"""Actual Android icon renderer regression; emulator-5580 only, no Xposed hook execution claim."""
import argparse,io,json,re,subprocess,time,xml.etree.ElementTree as E
from pathlib import Path
from PIL import Image
p=argparse.ArgumentParser();p.add_argument('--apk',type=Path,required=True);p.add_argument('--output',type=Path,required=True);args=p.parse_args()
out=args.output;out.mkdir(parents=True,exist_ok=True)
adb='C:/Users/wjy25/AppData/Local/Android/Sdk/platform-tools/adb.exe'
def call(*parts):
 r=subprocess.run([adb,'-s','emulator-5580',*parts],capture_output=True,timeout=40)
 assert r.returncode==0,r.stderr.decode(errors='replace');return r.stdout
def tree():
 for attempt in range(5):
  call('shell','rm','-f','/sdcard/dreamglass-icons.xml')
  if b'dumped' in call('shell','uiautomator','dump','/sdcard/dreamglass-icons.xml'):
   return E.fromstring(call('exec-out','cat','/sdcard/dreamglass-icons.xml'))
  time.sleep(.5)
 raise AssertionError('No fresh accessibility tree')
def rect(node):return list(map(int,re.findall(r'\d+',node.get('bounds',''))))
def capture(name,active):
 png=call('exec-out','screencap','-p');(out/(name+'.png')).write_bytes(png)
 ui=tree();(out/(name+'.xml')).write_bytes(E.tostring(ui,encoding='utf-8'))
 nodes=[n for n in ui.iter('node') if n.get('resource-id')=='android:id/icon']
 assert len(nodes)==12,len(nodes)
 im=Image.open(io.BytesIO(png)).convert('RGB')
 expected=[[(0,0,0) if active else (255,255,255),(255,255,255)],
  [(0,102,255)]*2,[(127,127,127) if active else (255,255,255),(128,128,128)],
  [(255,0,0)]*2,[(255,255,255)]*2,[(136,136,136)]*2]
 pixels=[]
 for i,node in enumerate(nodes):
  b=rect(node);color=im.getpixel(((b[0]+b[2])//2,(b[1]+b[3])//2));want=expected[i//2][i%2]
  assert max(abs(a-b) for a,b in zip(color,want))<=2,(active,i,color,want)
  corner=im.getpixel((b[0]+2,b[1]+2));backdrop=(255,255,255) if i%2==0 else (0,0,0)
  assert corner==backdrop,(active,i,'button backdrop changed',corner)
  pixels.append(list(color))
 return ui,pixels
assert call('shell','getprop','sys.boot_completed').strip()==b'1'
assert b'Success' in call('install','-r',str(args.apk.resolve()))
call('shell','am','force-stop','com.dreamglass.cos17')
call('shell','am','start','-n','com.dreamglass.cos17/com.dreamglass.cos17.IconContrastProbeActivity')
time.sleep(2)
state=json.loads(call('exec-out','run-as','com.dreamglass.cos17','cat','files/icon-contrast-probe.json'))
assert state['drawRestorationPassed'] and state['originalDrawExactlyOnce'] and not state['tintMutationDetected']
ui,on=capture('01-icons-auto-on',True)
button=next(n for n in ui.iter('node') if n.get('resource-id')=='android:id/button1');b=rect(button)
call('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2));time.sleep(.4)
_,off=capture('02-icons-auto-off',False)
result={'serial':'emulator-5580','androidRendererTested':True,'xposedHookExecuted':False,
 'physicalDeviceAccessed':False,'colorOsSystemUiTested':False,'rows':6,
 'localBlackWhitePassed':True,'coloredStatusPreserved':True,'originalAlphaPreserved':True,
 'untintedImagesPreserved':True,'missingBackgroundPreserved':True,'disabledIconsPreserved':True,
 'toggleOffRestoresNativeDrawing':True,'canvasRestoredAfterException':True,'drawableMutationDetected':False,
 'buttonBackdropsUnchanged':True,
 'onRgb':on,'offRgb':off}
(out/'summary.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps(result,indent=2))
