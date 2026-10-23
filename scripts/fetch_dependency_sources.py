"""Collect exact-version published dependency sources for the corresponding-source distribution."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import urllib.request
import zipfile

p = argparse.ArgumentParser()
p.add_argument('--output', type=Path, required=True)
args = p.parse_args()
root = Path(__file__).resolve().parents[1]
inventory = json.loads((root / 'licenses/dependency-inventory.json').read_text(encoding='utf-8'))
args.output.mkdir(parents=True, exist_ok=True)
components = inventory['components'] + inventory['compileOnly']

def collect(component):
    coordinate = component['coordinate']
    group, artifact, version = coordinate.split(':')
    filename = f'{artifact}-{version}-sources.jar'
    relative = group.replace('.', '/') + f'/{artifact}/{version}/{filename}'
    google = group.startswith('androidx.') or group == 'com.google.android.material'
    bases = (['https://dl.google.com/dl/android/maven2/', 'https://maven.aliyun.com/repository/google/'] if google
             else ['https://repo.maven.apache.org/maven2/', 'https://maven.aliyun.com/repository/public/'])
    target = args.output / (group + '--' + filename)
    errors = []
    chosen = None
    for base in bases:
        url = base + relative
        try:
            if not target.exists():
                request = urllib.request.Request(url, headers={'User-Agent': 'DreamGlass-source-release/1.0'})
                with urllib.request.urlopen(request, timeout=30) as response:
                    data = response.read(25_000_001)
                assert len(data) <= 25_000_000
                target.write_bytes(data)
            with zipfile.ZipFile(target) as jar:
                assert jar.testzip() is None
            chosen = url
            break
        except Exception as error:
            errors.append(str(error))
            target.unlink(missing_ok=True)
    if chosen is None:
        return {'coordinate': coordinate, 'error': errors}
    return {'coordinate': coordinate, 'file': target.name, 'url': chosen,
            'canonicalUrl': bases[0] + relative, 'sha256': hashlib.sha256(target.read_bytes()).hexdigest(),
            'bytes': target.stat().st_size}

with ThreadPoolExecutor(max_workers=6) as executor:
    records = list(executor.map(collect, components))
(args.output / 'manifest.json').write_text(json.dumps({'release': 'DreamGlass 1.0.0', 'sources': records},
                                                     ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
failed = [r for r in records if 'error' in r]
print(json.dumps({'downloaded': len(records) - len(failed), 'failed': failed}, ensure_ascii=False))
if failed:
    raise SystemExit(1)
