#!/usr/bin/env python3
"""Regression cases for native APK/AAR checks."""
import importlib.util
import io
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('native_check', Path(__file__).with_name('verify-native-package.py'))
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


def elf(machine, alignment=16384):
    data = bytearray(120)
    data[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<H', data, 18, machine)
    struct.pack_into('<Q', data, 32, 64)
    struct.pack_into('<HH', data, 54, 56, 1)
    struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, 0, 0, 0, len(data), len(data), alignment)
    return data


class NativePackageTests(unittest.TestCase):
    def test_both_machines(self):
        for abi, machine in check.MACHINES.items():
            self.assertEqual(check.elf_info(elf(machine), abi, 'test.so')['machine'], machine)

    def test_wrong_machine(self):
        with self.assertRaisesRegex(ValueError, 'machine'):
            check.elf_info(elf(183), 'x86_64', 'test.so')

    def test_four_kb_library_rejected(self):
        with self.assertRaisesRegex(ValueError, '16 KB'):
            check.elf_info(elf(183, 4096), 'arm64-v8a', 'test.so')

    def test_misaligned_segment_rejected(self):
        data = elf(183)
        struct.pack_into('<Q', data, 80, 4096)
        with self.assertRaisesRegex(ValueError, '16 KB'):
            check.elf_info(data, 'arm64-v8a', 'test.so')

    def test_truncated_binary_rejected(self):
        with self.assertRaisesRegex(ValueError, 'program header'):
            check.elf_info(elf(183)[:100], 'arm64-v8a', 'test.so')

    def test_thirty_two_bit_library_rejected(self):
        data = elf(183)
        data[4] = 1
        with self.assertRaisesRegex(ValueError, '64-bit'):
            check.elf_info(data, 'arm64-v8a', 'test.so')

    def package(self, directory, abis, omit=None):
        path = Path(directory)/'test.apk'
        payload = io.BytesIO()
        with zipfile.ZipFile(payload, 'w') as z:
            z.writestr('runtime/data', 'test')
        with zipfile.ZipFile(path, 'w') as z:
            for abi in abis:
                for name in check.REQUIRED:
                    if (abi, name) != omit:
                        z.writestr(f'lib/{abi}/{name}', payload.getvalue() if name.endswith('.zip.so')
                                   else elf(check.MACHINES[abi]))
        return path

    def test_complete_universal_package(self):
        with tempfile.TemporaryDirectory() as d:
            report = check.verify(self.package(d, list(check.MACHINES)), 'apk', list(check.MACHINES))
            self.assertEqual(report['abis'], list(check.MACHINES))

    def test_missing_backend_for_one_architecture(self):
        with tempfile.TemporaryDirectory() as d:
            path = self.package(d, list(check.MACHINES), ('x86_64', 'libgojni.so'))
            with self.assertRaisesRegex(ValueError, 'missing.*libgojni'):
                check.verify(path, 'apk', list(check.MACHINES))

    def test_single_abi_cannot_be_labeled_universal(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaisesRegex(ValueError, 'packaged ABIs'):
                check.verify(self.package(d, ['arm64-v8a']), 'apk', list(check.MACHINES))

    def test_single_abi_release(self):
        with tempfile.TemporaryDirectory() as d:
            check.verify(self.package(d, ['arm64-v8a']), 'apk', ['arm64-v8a'])


if __name__ == '__main__':
    unittest.main()
