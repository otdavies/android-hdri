#!/usr/bin/env python3
"""Replay production Kotlin against a private source ZIP using host OpenCV (not a phone benchmark)."""
import argparse, hashlib, json, os, pathlib, shutil, subprocess, urllib.request, zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('capture', type=pathlib.Path)
parser.add_argument('--work-dir', required=True, type=pathlib.Path)
parser.add_argument('--ref', help='Optional local Git revision for a before/after comparison')
args = parser.parse_args()
work = args.work_dir.resolve()
if work.is_relative_to(ROOT):
    parser.error('Keep private captures outside the public checkout.')
work.mkdir(parents=True, exist_ok=True)
cache = pathlib.Path.home() / '.cache/luma-sphere-replay'
cache.mkdir(parents=True, exist_ok=True)

def jar(group, artifact, version):
    name = f'{artifact}-{version}.jar'
    file = cache / name
    if not file.exists():
        url = f'https://repo.maven.apache.org/maven2/{group.replace(".","/")}/{artifact}/{version}/{name}'
        print(f'Downloading {name}', flush=True)
        temp = file.with_suffix('.partial')
        urllib.request.urlretrieve(url, temp)
        temp.replace(file)
    return str(file)

compiler = jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.0.21')
stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.0.21')
annotations = jar('org.jetbrains', 'annotations', '13.0')
compiler_cp = os.pathsep.join([compiler, stdlib, annotations,
    jar('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
    jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.6.4')])
cp = os.pathsep.join([stdlib, annotations, jar('org.json', 'json', '20240303'),
    jar('org.openpnp', 'opencv', '4.9.0-0')])
java = str(pathlib.Path(os.environ['JAVA_HOME']) / 'bin/java') if 'JAVA_HOME' in os.environ else 'java'

with zipfile.ZipFile(args.capture) as archive:
    manifest = archive.read('session.json')
    project = json.loads(manifest)
    project_id = project['id']
    if len(project_id) != 36 or any(c not in '0123456789abcdef-' for c in project_id):
        raise ValueError('Invalid capture ID')
    dest = work / 'sessions' / project_id
    dest.mkdir(parents=True, exist_ok=True)
    fingerprint = hashlib.sha256(manifest).hexdigest()
    marker = work / 'source.sha256'
    if marker.exists() and marker.read_text() != fingerprint:
        raise ValueError('Choose a separate work directory for each source capture')
    marker.write_text(fingerprint)
    if not (dest / 'session.json').exists():
        (dest / 'session.json').write_bytes(manifest)
    files = {e['file'] for c in project['captures'] for e in c['exposures']}
    for name in files:
        if pathlib.Path(name).name != name or '/' in name or '\\' in name:
            raise ValueError('Invalid exposure path')
        info = archive.getinfo(name)
        if info.file_size > 100_000_000:
            raise ValueError('Exposure is unexpectedly large')
        if not (dest / name).exists():
            with archive.open(info) as src, (dest / name).open('wb') as target:
                shutil.copyfileobj(src, target)

prefix = 'app/src/main/java/app/hdri/'
if args.ref:
    paths = subprocess.check_output(['git','ls-tree','-r','--name-only',args.ref,prefix],cwd=ROOT,text=True).splitlines()
else:
    paths = [str(p.relative_to(ROOT)) for p in (ROOT / prefix).rglob('*.kt')]
paths = [p for p in paths if (p.startswith(prefix+'processing/') and pathlib.Path(p).name not in ['ProcessingService.kt','SampleCapture.kt'])
         or p in [prefix+'core/Geometry.kt',prefix+'core/Radiance.kt',prefix+'data/SessionStore.kt']]
sources = []
for path in paths:
    if args.ref:
        file = work / 'engine-src' / pathlib.Path(path).name
        file.parent.mkdir(exist_ok=True)
        file.write_bytes(subprocess.check_output(['git','show',f'{args.ref}:{path}'],cwd=ROOT))
    else:
        file = ROOT / path
    sources.append(str(file))
sources += [str(p) for p in (ROOT / 'scripts/replay').glob('*.kt')]
output = work / 'engine.jar'
subprocess.run([java,'-cp',compiler_cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(output),*sources],check=True)
subprocess.run([java,'-Xmx3g','-cp',str(output)+os.pathsep+cp,'app.hdri.processing.MainKt',str(work)],check=True)
print(f'Private outputs: {dest}')
