#!/usr/bin/env python3
"""Host codec integration with synthetic audio; requires ffmpeg and ffprobe.
Build the identical codec configuration with HARMONY_CONVERTER_TARGETS=host
and HARMONY_CONVERTER_OUTPUT=/tmp/harmony-converter, then pass the executable.
These are host codec tests, not Android device tests.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile


def run(args, *, ok=True):
    result = subprocess.run([str(a) for a in args], stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=45)
    if ok and result.returncode:
        raise AssertionError(result.stderr.decode(errors='replace'))
    return result


def inspect(path):
    return json.loads(run(['ffprobe', '-v', 'error', '-show_streams', '-show_format',
                           '-of', 'json', path]).stdout)


def pcm(path):
    return run(['ffmpeg', '-v', 'error', '-i', path, '-map', '0:a:0',
                '-f', 's32le', '-c:a', 'pcm_s32le', '-']).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('converter', type=Path)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    converter = args.converter.resolve()
    passed = []
    common = ['-v', 'error', '-xerror', '-nostdin', '-y']
    run([converter, '-version'])
    with tempfile.TemporaryDirectory(prefix='harmony-audio-tests-') as directory:
        root = Path(directory)
        for codec, rate, bits, fragmented in [
            ('flac', 44100, 16, False), ('flac', 96000, 24, False),
            ('flac', 44100, 16, True), ('alac', 96000, 24, False),
        ]:
            label = f'{codec}-{rate}-{bits}-fragmented={fragmented}'
            source = root/'source.m4a'
            creation = ['ffmpeg', *common, '-f', 'lavfi', '-i',
                        f'aevalsrc=0.2*sin(2*PI*440*t)|0.17*sin(2*PI*997*t):s={rate}:d=1',
                        '-c:a', codec, '-sample_fmt',
                        ('s16' if bits == 16 else 's32') + ('p' if codec == 'alac' else ''),
                        '-strict', '-2']
            if fragmented:
                creation += ['-movflags', 'frag_keyframe+empty_moov']
            run([*creation, '-f', 'mp4', source])
            reference = pcm(source)
            output = root/'output.flac'
            command = [converter, *common, '-i', source, '-map', '0:a:0', '-vn',
                       '-c:a', 'copy' if codec == 'flac' else 'flac']
            if codec == 'alac':
                command += ['-compression_level', '5']
            run([*command, '-f', 'flac', output])
            assert output.read_bytes().startswith(b'fLaC'), label
            assert len(reference) > 1000 and reference == pcm(output), label
            passed.append(label + ': exact PCM')
        for artwork in ['none', 'png', 'jpg']:
            source = root/'tagged.flac'
            creation = ['ffmpeg', *common, '-f', 'lavfi', '-i',
                        'sine=frequency=523:sample_rate=44100:duration=1']
            if artwork != 'none':
                picture = root/f'cover.{artwork}'
                run(['ffmpeg', *common, '-f', 'lavfi', '-i', 'color=c=blue:s=32x32',
                     '-frames:v', '1', '-threads', '1', picture])
                creation += ['-i', picture, '-map', '0:a', '-map', '1:v',
                             '-c:v', 'copy', '-disposition:v', 'attached_pic']
            run([*creation, '-c:a', 'flac', '-metadata', 'title=Harmony fixture',
                 '-metadata', 'artist=Test artist', source])
            output = root/'output.mp3'
            run([converter, *common, '-i', source, '-map', '0:a:0', '-map', '0:v?',
                 '-map_metadata', '0', '-c:a', 'libmp3lame', '-b:a', '320k',
                 '-c:v', 'copy', '-id3v2_version', '3', '-write_id3v1', '1',
                 '-f', 'mp3', output])
            details = inspect(output)
            audio = next(s for s in details['streams'] if s['codec_type'] == 'audio')
            assert audio['codec_name'] == 'mp3' and int(audio['bit_rate']) == 320000
            assert details['format']['tags']['title'] == 'Harmony fixture'
            assert details['format']['tags']['artist'] == 'Test artist'
            covers = [s for s in details['streams'] if s['codec_type'] == 'video']
            assert bool(covers) == (artwork != 'none')
            assert len(pcm(output)) > 1000
            passed.append(f'MP3 320k + metadata + artwork={artwork}')
        aac = root/'lossy.m4a'
        run(['ffmpeg', *common, '-f', 'lavfi', '-i', 'sine=duration=1', '-c:a', 'aac', aac])
        attempt = run([converter, *common, '-i', aac, '-c:a', 'flac', root/'rejected.flac'], ok=False)
        assert attempt.returncode != 0, 'The converter must not silently decode lossy sources'
        passed.append('AAC input rejected')
        broken = root/'broken.m4a'
        broken.write_bytes(aac.read_bytes()[:32])
        assert run([converter, *common, '-i', broken, '-c:a', 'flac', root/'broken.flac'], ok=False).returncode != 0
        passed.append('Truncated input rejected')
    report = {'scope': 'host codec integration (not Android hardware)',
              'converter_sha256': hashlib.sha256(converter.read_bytes()).hexdigest(),
              'passed': passed, 'count': len(passed)}
    if args.report:
        args.report.write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
