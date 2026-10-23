"""UI acceptance on the isolated emulator only. Never select an attached physical device."""
from pathlib import Path
import argparse, subprocess, time, xml.etree.ElementTree as E, re, json

p=argparse.ArgumentParser();p.add_argument('--apk',type=Path,required=True);p.add_argument('--install',action='store_true');p.add_argument('--output',type=Path,default=Path('build/release-ui-qa'));args=p.parse_args()
adb=Path('C:/Users/wjy25/AppData/Local/Android/Sdk/platform-tools/adb.exe')
serial='emulator-5580';assert serial=='emulator-5580'
out=args.output;out.mkdir(parents=True,exist_ok=True)
events=[]
def call(*parts,check=True):
    result=subprocess.run([str(adb),'-s',serial,*parts],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=40)
    if check and result.returncode:raise RuntimeError(str(parts)+': '+result.stderr.decode(errors='replace')[:1200])
    return result.stdout
def xml():
    call('shell','uiautomator','dump','/sdcard/dreamglass-test-window.xml')
    return E.fromstring(call('exec-out','cat','/sdcard/dreamglass-test-window.xml'))
def nodes():return list(xml().iter('node'))
def tap_text(value,scroll=False):
    for _ in range(10 if scroll else 1):
        found=next((n for n in nodes() if n.get('text')==value or n.get('content-desc')==value),None)
        if found is not None:
            nums=list(map(int,re.findall(r'\d+',found.get('bounds',''))))
            if len(nums)==4 and nums[2]>nums[0] and nums[3]>nums[1]:
                call('shell','input','tap',str((nums[0]+nums[2])//2),str((nums[1]+nums[3])//2));time.sleep(.5)
                events.append('tap:'+value);return
        if scroll:call('shell','input','swipe','540','1800','540','500','350');time.sleep(.3)
    raise AssertionError('UI control not found: '+value)
def shot(name,expect):
    tree=xml();text='\n'.join(n.get('text','') for n in tree.iter('node'))
    for word in expect:assert word in text,(name,word,text[:2000])
    (out/(name+'.xml')).write_bytes(E.tostring(tree,encoding='utf-8'))
    (out/(name+'.png')).write_bytes(call('exec-out','screencap','-p'))
    events.append('screenshot:'+name)
def back():call('shell','input','keyevent','4');time.sleep(.4)

assert call('shell','getprop','sys.boot_completed').strip()==b'1'
if args.install:
    result=call('install','-r',str(args.apk.resolve())).decode();assert 'Success' in result,result
call('shell','am','force-stop','com.dreamglass.cos17')
call('shell','am','start','-f','0x10008000','-n','com.dreamglass.cos17/com.coloros16.liquidglass.ui.DreamActivity')
time.sleep(1.2)
shot('01-workbench',['DreamGlass','工作台','1.0.0'])
tap_text('材质');shot('02-material-studio',['材质工作室','材质预览'])
tap_text('状态');shot('03-runtime-status',['运行状态','目标进程记录'])
tap_text('设置');shot('04-preferences',['偏好与工具','系统外观','应用入口'])
tap_text('显示桌面图标')
disabled=call('shell','dumpsys','package','com.dreamglass.cos17').decode(errors='replace')
assert re.search(r'disabledComponents:\s+com\.dreamglass\.cos17\.LauncherAlias',disabled), 'launcher alias did not hide'
tap_text('显示桌面图标')
enabled=call('shell','dumpsys','package','com.dreamglass.cos17').decode(errors='replace')
assert not re.search(r'disabledComponents:\s+com\.dreamglass\.cos17\.LauncherAlias',enabled), 'launcher alias did not restore'
tap_text('材质参数');shot('05-material-parameters',['参数详情','材质参数'])
back();shot('06-return-to-preferences',['偏好与工具','系统外观'])
tap_text('渲染开关');shot('07-render-switches',['参数详情','渲染开关'])
back();tap_text('关于 DreamGlass',scroll=True);shot('08-about-licenses',['DreamGlass','1.0.0','ColorOS16-LiquidGlass'])
tap_text('GNU GPL v3 全文',scroll=True);shot('09-offline-gpl',['GNU GPL v3 全文','GNU GENERAL PUBLIC LICENSE'])
back();tap_text('项目来源与修改声明',scroll=True);shot('10-attribution',['项目来源与修改声明','AYwlilwYA'])
back();back()
call('shell','settings','put','system','font_scale','1.3')
call('shell','am','force-stop','com.dreamglass.cos17')
call('shell','am','start','-n','com.dreamglass.cos17/com.coloros16.liquidglass.ui.DreamActivity');time.sleep(.8)
shot('11-large-font-workbench',['DreamGlass','工作台'])
tap_text('设置');shot('12-large-font-preferences',['偏好与工具','应用入口'])
call('shell','settings','put','system','font_scale','1.0')
crashes=call('logcat','-d','-v','brief','AndroidRuntime:E','*:S').decode(errors='replace')
(out/'android-runtime-log.txt').write_text(crashes,encoding='utf-8')
assert 'Process: com.dreamglass.cos17' not in crashes,crashes[:2400]
report={'serial':serial,'isolatedEmulator':True,'androidApi':33,'apk':args.apk.name,'events':events,'appCrashDetected':False,'launcherAliasHideRestorePassed':True,
        'colorOsHookTested':False,'physicalDeviceAccessed':False}
(out/'ui-qa-summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
