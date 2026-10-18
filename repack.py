#!/usr/bin/env python3
"""Repack a linked APK with the dex and native libraries.

Usage: repack.py <base.apk> <out.apk> <entry>[:<path-on-disk>] ...

The Android `zip` utility is inconsistent across builds: some versions accept an
absolute archive path and add members to it, others treat that path as a member
name and silently produce an archive missing everything. Doing the repack here
removes that class of failure and drops a dependency.

Entries keep the compression the APK needs: .so files are STORED when the
manifest sets extractNativeLibs="false" (the loader maps them in place), and
deflated otherwise. Everything else is deflated.
"""
import sys
import zipfile

# Entries that must be uncompressed so the loader can mmap them in place.
STORED_SUFFIXES = ('.so',)


def repack(base, out, entries):
    with zipfile.ZipFile(base, 'r') as src:
        names = src.namelist()
        blobs = {n: src.read(n) for n in names}
        infos = {n: src.getinfo(n) for n in names}

    with zipfile.ZipFile(out, 'w') as dst:
        for name in names:
            zi = infos[name]
            # Preserve the original compression choice for the base APK's own
            # entries; resources.arsc in particular must stay as aapt2 wrote it.
            dst.writestr(zi, blobs[name])

        for spec in entries:
            if ':' in spec:
                arcname, disk = spec.split(':', 1)
            else:
                arcname, disk = spec, spec
            with open(disk, 'rb') as f:
                data = f.read()
            method = (zipfile.ZIP_STORED
                      if arcname.endswith(STORED_SUFFIXES)
                      else zipfile.ZIP_DEFLATED)
            dst.writestr(arcname, data, compress_type=method)


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    repack(argv[1], argv[2], argv[3:])
    return 0


if __name__ == '__main__':
    raise SystemExit(main(sys.argv))
