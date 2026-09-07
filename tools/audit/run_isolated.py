"""Isolated Kotlin/Compose compilation and regression harnesses (not an Android APK build).

Supply a Kotlin 2.1.0 distribution and a directory containing Android 35 android.jar
and the project's dependency classes.jar files (AARs must first be extracted).
See README.md for the exact limitations of this check.
"""
from pathlib import Path
import argparse, os, re, subprocess, tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--kotlin-home', type=Path, required=True)
parser.add_argument('--dependencies', type=Path, required=True)
parser.add_argument('--compiler-jar', type=Path)
parser.add_argument('--java', default='java')
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
audit = Path(__file__).resolve().parent
compiler = args.compiler_jar or args.kotlin_home/'lib/kotlin-compiler.jar'
dependencies = list(args.dependencies.glob('*.jar'))
# Prefer the standalone org.json implementation to Android's throwing JVM stubs.
dependencies.sort(key=lambda p: (0 if p.name == 'json.jar' else 1, p.name))
classpath = os.pathsep.join(map(str, dependencies))
compiler_cp = os.pathsep.join([str(compiler), str(args.kotlin_home/'lib/*')])
java = [args.java, '-XX:-UsePerfData']
base = java + ['-Xmx3g', '-cp', compiler_cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
               '-kotlin-home', str(args.kotlin_home), '-no-reflect', '-jvm-target', '17']

def compile_files(sources, dest, cp=classpath, compose=False, friend=None):
    options = ['-classpath', cp, '-d', str(dest)]
    if compose: options += ['-Xplugin='+str(args.kotlin_home/'lib/compose-compiler-plugin.jar')]
    if friend: options += ['-Xfriend-paths='+str(friend)]
    subprocess.run(base + options + list(map(str, sources)), check=True)

def run(main, jars, extra=()):
    cp = os.pathsep.join(list(map(str, jars))+[classpath,str(args.kotlin_home/'lib/*')])
    subprocess.run(java + ['-cp', cp, main] + list(extra), check=True)

with tempfile.TemporaryDirectory(prefix='harmony-audit-') as temporary:
    out = Path(temporary)
    resources = out/'R.kt'
    resources.write_text('package com.harmony.playback.service\nobject R { object drawable { const val ic_car_favorite=1; const val ic_car_shuffle=2 } }\n')
    sources = sorted(f for f in root.rglob('*.kt') if '/src/main/' in f.as_posix() and '/build-logic/' not in f.as_posix())
    app = out/'app.jar'
    compile_files(sources+[resources], app, compose=True)
    print(f'PASS: {len(sources)} production Kotlin files compiled', flush=True)
    tests = sorted(f for f in root.rglob('*.kt') if '/src/test/' in f.as_posix())
    classes = []
    for test in tests:
        text = test.read_text()
        package = re.search(r'^package (\S+)', text, re.M).group(1)
        classes += [package+'.'+name for name in re.findall(r'^class (\w+Test)\b', text, re.M)]
    compile_files(tests, out/'tests.jar', os.pathsep.join([str(app),classpath]), friend=app)
    run('org.junit.runner.JUnitCore', [out/'tests.jar',app], classes)
    downloads = root/'feature/downloads/src/main/kotlin/com/harmony/feature/downloads'
    tags = [root/'feature/settings/src/main/kotlin/com/harmony/feature/settings/TagEditorViewModel.kt']
    tags += [downloads/f for f in ['SpotifyPlaylistClient.kt','SpotifyAuthorization.kt','SpotifyApiUrlPolicy.kt']]
    tags += list((audit/'audit-harness').glob('*.kt'))
    compile_files(tags, out/'async.jar', os.pathsep.join([str(app),classpath]))
    run('RunKt', [out/'async.jar', app])
    playback = root/'playback/service/src/main/kotlin/com/harmony/playback/service/player'
    player_sources = [playback/f for f in ['CrossfadeController.kt','ReplayGainAudioProcessor.kt']]
    player_sources += list((audit/'playback-harness').glob('*.kt'))
    compile_files(player_sources, out/'player.jar')
    run('RunKt', [out/'player.jar'])
subprocess.run([os.sys.executable, str(audit/'verify_database.py')], check=True)
