"""Check the actual Android RuntimeShader sampling path, isolated emulator-5580 only.
Requires the debug-only SamplingProbeActivity; never included in a release APK.
"""
from pathlib import Path
import argparse,io,json,subprocess,time
from PIL import Image

p=argparse.ArgumentParser();p.add_argument('--apk',type=Path,required=True)
p.add_argument('--output',type=Path,required=True);args=p.parse_args()
out=args.output;out.mkdir(parents=True,exist_ok=True)
adb='C:/Users/wjy25/AppData/Local/Android/Sdk/platform-tools/adb.exe'
def call(*parts):
 r=subprocess.run([adb,'-s','emulator-5580',*parts],capture_output=True,timeout=40)
 assert r.returncode==0,r.stderr.decode(errors='replace');return r.stdout
assert call('shell','getprop','sys.boot_completed').strip()==b'1'
assert b'Success' in call('install','-r',str(args.apk.resolve()))
call('shell','am','force-stop','com.dreamglass.cos17')
call('shell','am','start','-n','com.dreamglass.cos17/com.dreamglass.cos17.SamplingProbeActivity')
time.sleep(2)
png=call('exec-out','screencap','-p');(out/'android-gpu-sampling.png').write_bytes(png)
im=Image.open(io.BytesIO(png)).convert('RGB');w,h=im.size
rows=[]
for y in range(h):
 line=[im.getpixel((x,y)) for x in range(48,w-48)]
 if sum(max(p)-min(p)<=1 for p in line)>len(line)*.99 and 80<sum(p[0] for p in line)/len(line)<170:
  rows.append(y)
groups=[]
for y in rows:
 if not groups or y>groups[-1][-1]+1:groups.append([y])
 else:groups[-1].append(y)
groups=[g for g in groups if len(g)>100]
assert len(groups)==3,[(g[0],g[-1]) for g in groups]
metrics=[]
for label,group in zip(['nearest-reference','production-source','production-low-res'],groups):
 y=group[len(group)//2];values=[im.getpixel((x,y))[0] for x in range(48,w-48)]
 metrics.append({'panel':label,'row':y,'distinctGrayLevels':len(set(values)),
  'largestAdjacentJump':max(abs(a-b) for a,b in zip(values,values[1:]))})
assert metrics[0]['distinctGrayLevels']<=32 and metrics[0]['largestAdjacentJump']>=7,metrics
for row in metrics[1:]:
 assert row['distinctGrayLevels']>150 and row['largestAdjacentJump']<=2,metrics
result={'serial':'emulator-5580','androidRuntimeShaderGpuTested':True,
 'physicalDeviceAccessed':False,'colorOsSystemUiTested':False,'panels':metrics,
 'productionBitmapInputsInterpolate':True}
(out/'summary.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
print(json.dumps(result,indent=2))
