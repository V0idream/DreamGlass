"""Generate offline legal assets from the exact resolved release artifacts, not a guessed library list.

Run :app:writeReleaseComponentInventory first. No network access or device access.
"""
from pathlib import Path
import argparse, hashlib, io, json, re, shutil, xml.etree.ElementTree as E, zipfile

p=argparse.ArgumentParser()
p.add_argument('--inventory', type=Path, required=True)
p.add_argument('--gradle-cache', type=Path, required=True)
args=p.parse_args()
root=Path(__file__).resolve().parents[1]
release_version=re.search(r'versionName\s*=\s*"([^"]+)"',(root/'app/build.gradle.kts').read_text(encoding='utf-8'))[1]
assets=root/'app/src/main/assets/legal';assets.mkdir(parents=True,exist_ok=True)
copies=root/'licenses/third-party';copies.mkdir(parents=True,exist_ok=True)
NS='{http://maven.apache.org/POM/4.0.0}'
components={};retained=[]

def retain(archive, component, depth=0):
    with zipfile.ZipFile(archive) as z:
        for name in z.namelist():
            base=Path(name).name.lower()
            if re.match(r'^(license|notice|copying|copyright)([._-]|$)',base) and not name.endswith('/'):
                data=z.read(name)
                assert len(data)<4_000_000
                filename=re.sub(r'[^A-Za-z0-9._-]','_',component+'--'+name)
                (copies/filename).write_bytes(data)
                retained.append({'component':component,'originalEntry':name,'copy':filename,'sha256':hashlib.sha256(data).hexdigest()})
            if depth==0 and name=='classes.jar':retain(io.BytesIO(z.read(name)),component,1)

for line in args.inventory.read_text(encoding='utf-8').splitlines():
    group,name,version,file=line.split('\t')
    coordinate=f'{group}:{name}:{version}'
    pomfiles=list((args.gradle_cache/group/name/version).glob('*/*.pom'))
    assert pomfiles,coordinate+' has no cached POM'
    pom=E.parse(pomfiles[0]).getroot()
    licenses=[{'name':n.findtext(NS+'name'),'url':n.findtext(NS+'url')} for n in pom.findall(NS+'licenses/'+NS+'license')]
    if not licenses:
        assert group=='com.google.guava' and name=='listenablefuture',coordinate
        licenses=[{'name':'Apache License 2.0','url':'https://github.com/google/guava/blob/master/LICENSE'}]
    assert all('apache' in (x['name'] or '').lower() and '2' in (x['name'] or '') for x in licenses), (coordinate,licenses)
    components[coordinate]={'coordinate':coordinate,'license':'Apache-2.0','pomLicenses':licenses,
        'source':pom.findtext(NS+'url') or ('https://android.googlesource.com/platform/frameworks/support' if group.startswith('androidx.') else None),
        'redistributed':True}
    retain(Path(file),coordinate)

for name in ['interface','service']:
    coordinate=f'io.github.libxposed:{name}:102.0.0'
    file=root/f'app/libs/libxposed-{name}-102.0.0.aar'
    components[coordinate]={'coordinate':coordinate,'license':'Apache-2.0','source':'https://github.com/libxposed/service',
        'redistributed':True,'modified':'AAR metadata minCompileSdk changed from 37 to 36; classes/resources unchanged.'}
    retain(file,coordinate)
retain(root/'gradle/wrapper/gradle-wrapper.jar','Gradle-wrapper--build-tool')

inventory={'release':'DreamGlass '+release_version,'components':list(components.values()),
    'compileOnly':[{'coordinate':'io.github.libxposed:api:102.0.0','license':'Apache-2.0','redistributedInApk':False,'source':'https://github.com/libxposed/api'}],
    'buildOnly':'Gradle wrapper is included in the source archive, not the APK. JUnit, the Kotlin compiler, Android SDK, desktop Skia and the emulator are not bundled in the APK.',
    'retainedOriginalNotices':retained}
(root/'licenses/dependency-inventory.json').write_text(json.dumps(inventory,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
lines=['DreamGlass '+release_version+' — 实际发布依赖清单','',
       '下列运行时组件按 Apache License 2.0 分发；许可全文另附。版本来自 Gradle 的 releaseRuntimeClasspath。',
       '重复的 JAR/AAR 变体按同一坐标合并，不把仅编译/测试工具误称为 APK 运行库。','']
for component in components.values():
    lines += [component['coordinate'],'  Apache-2.0','  '+str(component.get('source') or component['pomLicenses'][0]['url'])]
lines += ['', '仅编译接口（未打入 APK）：io.github.libxposed:api:102.0.0，Apache-2.0。',
          '本地 interface/service AAR 仅修改 minCompileSdk 元数据 37→36；类和资源不改。',
          '构建工具与原始版权附件见配套源码包 licenses/。']
(assets/'DEPENDENCIES.txt').write_text('\n'.join(lines)+'\n',encoding='utf-8')
for source,name in [(root/'LICENSE','GPL-3.0.txt'),(root/'licenses/Apache-2.0.txt','Apache-2.0.txt'),
                    (root/'THIRD-PARTY-NOTICES.md','THIRD-PARTY-NOTICES.txt'),(root/'NOTICE','NOTICE.txt')]:
    shutil.copyfile(source,assets/name)
target=assets/'third-party';target.mkdir(exist_ok=True)
for source in copies.iterdir():
    if source.is_file():shutil.copyfile(source,target/source.name)
(assets/'dependency-inventory.json').write_text(json.dumps(inventory,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('Runtime components:',len(components),'retained original license/notice entries:',len(retained))
