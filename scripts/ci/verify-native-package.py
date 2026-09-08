#!/usr/bin/env python3
"""Reject incomplete/wrong-ABI native packages before distributing Harmony."""
import argparse
import io
import json
from pathlib import Path
import struct
import zipfile

MACHINES = {'arm64-v8a': 183, 'x86_64': 62}
PAGE_SIZE = 16384
SYSTEM_LIBS = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libz.so', 'libandroid.so'}
REQUIRED = {'libgojni.so', 'libharmonydsp.so', 'libharmonyshuffle.so',
            'libharmony_flac.so', 'libffmpeg.so', 'libffmpeg.zip.so',
            'libpython.so', 'libpython.zip.so'}


def elf_info(data, abi, name):
    if len(data) < 64 or data[:6] != b'\x7fELF\x02\x01':
        raise ValueError(f'{name}: expected a 64-bit little-endian ELF')
    machine = struct.unpack_from('<H', data, 18)[0]
    if machine != MACHINES[abi]:
        raise ValueError(f'{name}: ELF machine {machine}, expected {MACHINES[abi]}')
    phoff = struct.unpack_from('<Q', data, 32)[0]
    phsize, phcount = struct.unpack_from('<HH', data, 54)
    if phsize < 56 or not phcount or phoff + phsize * phcount > len(data):
        raise ValueError(f'{name}: invalid program header table')
    headers = [struct.unpack_from('<IIQQQQQQ', data, phoff + i * phsize) for i in range(phcount)]
    loads = [h for h in headers if h[0] == 1]
    if not loads:
        raise ValueError(f'{name}: no loadable segments')
    for _, _, offset, address, _, size, _, alignment in loads:
        if offset + size > len(data):
            raise ValueError(f'{name}: truncated loadable segment')
        if alignment < PAGE_SIZE or (offset - address) % PAGE_SIZE:
            raise ValueError(f'{name}: LOAD segment is not 16 KB compatible')
    dynamic = []
    for h in headers:
        if h[0] == 2:
            if h[2] + h[5] > len(data):
                raise ValueError(f'{name}: truncated dynamic table')
            for i in range(h[2], h[2] + h[5], 16):
                tag, value = struct.unpack_from('<qQ', data, i)
                if tag == 0:
                    break
                dynamic.append((tag, value))
    needed_offsets = [v for t, v in dynamic if t == 1]
    straddr = next((v for t, v in dynamic if t == 5), None)
    needed = []
    if needed_offsets:
        strbase = next((h[2] + straddr - h[3] for h in loads
                        if straddr is not None and h[3] <= straddr < h[3] + h[5]), None)
        if strbase is None:
            raise ValueError(f'{name}: dynamic string table is missing')
        for n in needed_offsets:
            start = strbase + n
            end = data.find(b'\0', start)
            if start >= len(data) or end < 0:
                raise ValueError(f'{name}: invalid dynamic library name')
            needed.append(data[start:end].decode('ascii'))
    if name.endswith('libharmony_flac.so') and set(needed) - SYSTEM_LIBS:
        raise ValueError(f'{name}: converter has non-system dependencies: {needed}')
    return {'machine': machine, 'load_alignment': min(h[7] for h in loads), 'needed': needed}


def verify(path, mode, abis):
    report = {'file': path.name, 'mode': mode, 'abis': abis, 'libraries': {}}
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise ValueError('corrupted ZIP entry')
        prefix = 'lib/' if mode == 'apk' else 'jni/'
        entries = [n for n in archive.namelist() if n.startswith(prefix) and n.endswith('.so')]
        actual_abis = {n.split('/')[1] for n in entries}
        if actual_abis != set(abis):
            raise ValueError(f'packaged ABIs {sorted(actual_abis)} != expected {abis}')
        for abi in abis:
            names = {n.split('/')[-1] for n in entries if n.startswith(f'{prefix}{abi}/')}
            missing = (REQUIRED if mode == 'apk' else {'libgojni.so'}) - names
            if missing:
                raise ValueError(f'{abi}: missing {sorted(missing)}')
        for name in entries:
            data = archive.read(name)
            if name.endswith('.zip.so'):
                with zipfile.ZipFile(io.BytesIO(data)) as payload:
                    if payload.testzip() is not None:
                        raise ValueError(f'{name}: corrupted runtime ZIP')
                continue
            report['libraries'][name] = elf_info(data, name.split('/')[1], name)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('package', type=Path)
    parser.add_argument('--mode', choices=['apk', 'aar'], default='apk')
    parser.add_argument('--abi', choices=[*MACHINES, 'universal'], default='universal')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    abis = list(MACHINES) if args.abi == 'universal' else [args.abi]
    try:
        report = verify(args.package, args.mode, abis)
    except (ValueError, OSError, zipfile.BadZipFile, struct.error) as exc:
        parser.exit(1, f'Native package verification failed: {exc}\n')
    if args.report:
        args.report.write_text(json.dumps(report, indent=2)+'\n')
    print(f'Native package verified: {args.package.name}; {", ".join(abis)}; 16 KB ELF alignment')


if __name__ == '__main__':
    main()
