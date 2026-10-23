"""DreamGlass 1.0.2 background-blur UI checks on the isolated emulator-5580 only; no framework/phone claims."""
from pathlib import Path
import argparse, json, re, subprocess, time, xml.etree.ElementTree as E

p=argparse.ArgumentParser();p.add_argument('--apk',type=Path,required=True)
p.add_argument('--output',type=Path,required=True);args=p.parse_args()
adb=Path('C:/Users/wjy25/AppData/Local/Android/Sdk/platform-tools/adb.exe')
out=args.output;out.mkdir(parents=True,exist_ok=True)
def call(*parts):
 r=subprocess.run([str(adb),'-s','emulator-5580',*parts],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=40)
 assert r.returncode==0,r.stderr.decode(errors='replace');return r.stdout
def tree():
 for attempt in range(5):
  call('shell','rm','-f','/sdcard/dreamglass-test-window.xml')
  result=call('shell','uiautomator','dump','/sdcard/dreamglass-test-window.xml')
  if b'dumped' not in result:
   time.sleep(.7);continue
  data=call('exec-out','cat','/sdcard/dreamglass-test-window.xml')
  try:return E.fromstring(data)
  except E.ParseError:
   if attempt==4:raise
   time.sleep(.7)
 raise AssertionError('No fresh accessibility tree after retries')
def bounds(node):return list(map(int,re.findall(r'\d+',node.get('bounds',''))))
def find(text=None,id=None):
 for n in tree().iter('node'):
  if (text is None or n.get('text')==text) and (id is None or n.get('resource-id','').endswith('/'+id)):return n
 raise AssertionError((text,id))
def tap(text,scroll=False):
 for attempt in range(6 if scroll else 1):
  try:
   b=bounds(find(text=text));call('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2));time.sleep(.4);return
  except AssertionError:
   if not scroll:raise
   sc=next(n for n in tree().iter('node') if n.get('class')=='android.widget.ScrollView');b=bounds(sc)
   call('shell','input','swipe',str((b[0]+b[2])//2),str(b[1]+(b[3]-b[1])*3//4),str((b[0]+b[2])//2),str(b[1]+(b[3]-b[1])//4),'300');time.sleep(.3)
 raise AssertionError('Could not scroll to '+text)
def shot(name):
 t=tree();(out/(name+'.xml')).write_bytes(E.tostring(t,encoding='utf-8'))
 (out/(name+'.png')).write_bytes(call('exec-out','screencap','-p'))
 return t
def open_material():
 call('shell','am','force-stop','com.dreamglass.cos17')
 call('shell','am','start','-f','0x10008000','-n','com.dreamglass.cos17/com.coloros16.liquidglass.ui.DreamActivity')
 time.sleep(.7);tap('设置');tap('材质参数',scroll=True);time.sleep(.5)
def set_slider(fraction,expected):
 b=bounds(find(id='seekButtonBlurRadius'))
 # Tapping inside the outer thumb padding clamps to exact endpoints.
 x=max(b[0]+2,min(b[2]-2,b[0]+int((b[2]-b[0])*fraction)));y=(b[1]+b[3])//2
 call('shell','input','tap',str(x),str(y));time.sleep(.3)
 if expected is not None:assert find(id='buttonBlurRadiusValue').get('text')==expected

assert call('shell','getprop','sys.boot_completed').strip()==b'1'
assert b'Success' in call('install','-r',str(args.apk.resolve()))
open_material()
normal=shot('01-button-blur-default')
find(text='按钮背景模糊')
assert not any(n.get('text')=='按钮整体透明度' for n in normal.iter('node'))
preview=bounds(find(id='materialPreview'))
scroll=next(n for n in normal.iter('node') if n.get('class')=='android.widget.ScrollView')
sb=bounds(scroll)
screen_bottom=max(bounds(n)[3] for n in normal.iter('node') if n.get('bounds'))
assert preview[1]<screen_bottom*.2
assert (sb[3]-sb[1])/screen_bottom>.55
for value, fraction in [(0,0),(32,.5),(64,1)]:
    set_slider(fraction,str(value)+' px')
    shot('blur-'+str(value))
call('shell','input','keyevent','4');time.sleep(.4);find(text='偏好与工具')
errors=call('logcat','-d','-v','brief','AndroidRuntime:E','*:S').decode(errors='replace')
assert 'Process: com.dreamglass.cos17' not in errors,errors[:1200]
result={'serial':'emulator-5580','physicalDeviceAccessed':False,'colorOsHookTested':False,
    'blurRadiusEndpointsPassed':[0,32,64],'oldTransparencyControlAbsent':True,
    'previewTopFraction':preview[1]/screen_bottom,
    'parameterScrollHeightFraction':(sb[3]-sb[1])/screen_bottom,
    'navigationBackPassed':True,'frameworkPreferencePersistenceTested':False,
    'appCrashDetected':False,'screenshots':4}
(out/'summary.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
print(json.dumps(result,indent=2))
