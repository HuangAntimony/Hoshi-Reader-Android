#!/usr/bin/env python3
"""Build the optional FFmpeg component independently of the base APK.

Use the repository-pinned NDK and CMake. Commit the generated catalog together
with decoder/config changes, then publish the hash-named binaries before APKs.
"""
import argparse
import hashlib
import gzip
import io
import tarfile
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/cpp/audio'
CATALOG = ROOT / 'app/src/main/assets/transcription-audio-runtime.json'
ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86_64')


def source_hash():
    digest = hashlib.sha256()
    for name in ('app/src/main/cpp/audio/CMakeLists.txt', 'app/src/main/cpp/audio/audio_decoder.cpp', 'tools/build-transcription-audio.py'):
        digest.update(name.encode())
        digest.update((ROOT / name).read_bytes())
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Verify catalog matches native sources without building')
    parser.add_argument('--ndk', type=Path, default=os.environ.get('ANDROID_NDK_HOME'))
    parser.add_argument('--cmake', default='cmake')
    args = parser.parse_args()
    if args.check:
        if json.loads(CATALOG.read_text())['sourceSha256'] != source_hash():
            raise SystemExit('Audio component sources changed: rebuild and publish its catalog before shipping an APK')
        return
    if args.ndk is None:
        parser.error('--ndk or ANDROID_NDK_HOME is required')
    output = ROOT / 'build/transcription-audio'
    distribution = output / 'distribution'
    distribution.mkdir(parents=True, exist_ok=True)
    host = 'darwin-x86_64' if platform.system() == 'Darwin' else 'linux-x86_64'
    strip = args.ndk / 'toolchains/llvm/prebuilt' / host / 'bin/llvm-strip'
    result = {}
    for abi in ABIS:
        build = output / abi
        subprocess.run([args.cmake, '-S', str(SOURCE), '-B', str(build),
                        '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_CXX_STANDARD=23',
                        '-DCMAKE_TOOLCHAIN_FILE=' + str(args.ndk / 'build/cmake/android.toolchain.cmake'),
                        '-DANDROID_ABI=' + abi, '-DANDROID_PLATFORM=android-26',
                        '-DANDROID_STL=c++_static'], check=True)
        subprocess.run([args.cmake, '--build', str(build), '--target', 'hoshiaudio_jni', '-j4'], check=True)
        binary = build / 'libhoshiaudio_jni.so'
        subprocess.run([str(strip), '--strip-unneeded', str(binary)], check=True)
        digest = hashlib.sha256(binary.read_bytes()).hexdigest()
        name = abi + '-' + digest + '-libhoshiaudio_jni.so'
        shutil.copyfile(binary, distribution / name)
        result[abi] = [dict(name=name, bytes=binary.stat().st_size, sha256=digest)]
    identity = hashlib.sha256(json.dumps(result, sort_keys=True).encode()).hexdigest()[:16]
    tag = 'transcription-audio-' + identity
    for files in result.values():
        for spec in files:
            spec['url'] = 'https://github.com/HuangAntimony/Hoshi-Reader-Android/releases/download/' + tag + '/' + spec['name']
    CATALOG.write_text(json.dumps(dict(sourceSha256=source_hash(), files=result), indent=2) + '\n')
    archive = output / ('audio-source-' + source_hash() + '.tar.gz')
    with archive.open('wb') as raw, gzip.GzipFile(fileobj=raw, mode='wb', mtime=0, filename='') as compressed:
        with tarfile.open(fileobj=compressed, mode='w') as sources:
            for name in ('app/src/main/cpp/audio/CMakeLists.txt', 'app/src/main/cpp/audio/audio_decoder.cpp',
                         'tools/build-transcription-audio.py', 'app/src/main/res/raw/ffmpeg_license.txt', 'LICENSE'):
                data = (ROOT / name).read_bytes()
                info = tarfile.TarInfo(name)
                info.size = len(data)
                info.mode = 0o644
                sources.addfile(info, io.BytesIO(data))
    print('Built', tag, 'in', distribution)
    print('Source archive:', archive)


if __name__ == '__main__':
    main()
