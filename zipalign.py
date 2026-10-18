#!/usr/bin/env python3
"""Pure-python zipalign (4-byte alignment of uncompressed entries + data).

Usage: zipalign.py <in.apk> <out.apk> [alignment]
Implements the same rules as the SDK zipalign for a normal APK: every
uncompressed (STORED) entry's *data* offset must be a multiple of the
alignment. Central directory offsets are rewritten accordingly.
"""
import sys
import struct
import zipfile

LFH = 0x04034b50
CDH = 0x02014b50
EOCD = 0x06054b50


def align_apk(src, dst, align=4):
    data = bytearray(open(src, 'rb').read())

    # --- locate EOCD (scan from the end; comment may follow) ---
    eocd = -1
    for i in range(len(data) - 22, max(-1, len(data) - 22 - 65535), -1):
        if struct.unpack_from('<I', data, i)[0] == EOCD:
            eocd = i
            break
    if eocd < 0:
        raise SystemExit('EOCD not found')

    cd_size, cd_off = struct.unpack_from('<II', data, eocd + 12)
    count = struct.unpack_from('<H', data, eocd + 10)[0]

    # --- parse central directory into records we can rewrite ---
    entries = []
    p = cd_off
    for _ in range(count):
        if struct.unpack_from('<I', data, p)[0] != CDH:
            raise SystemExit('bad CDH at %d' % p)
        method = struct.unpack_from('<H', data, p + 10)[0]
        csize = struct.unpack_from('<I', data, p + 20)[0]
        usize = struct.unpack_from('<I', data, p + 24)[0]
        nlen, elen, clen = struct.unpack_from('<HHH', data, p + 28)
        lho = struct.unpack_from('<I', data, p + 42)[0]
        entries.append(dict(cd=p, method=method, csize=csize, usize=usize,
                            nlen=nlen, elen=elen, clen=clen, lho=lho))
        p += 46 + nlen + elen + clen

    # --- rebuild local entries, inserting padding where needed ---
    out = bytearray()
    # copy the prefix before the first local header (normally nothing)
    first_lho = min(e['lho'] for e in entries)
    out += data[:first_lho]

    for e in entries:
        lho = e['lho']
        nlen, elen = struct.unpack_from('<HH', data, lho + 26)
        lfh_len = 30 + nlen + elen
        new_lho = len(out)
        # patch the local header's extra-field length if we add padding
        lfh = bytearray(data[lho:lho + lfh_len])
        payload = data[lho + lfh_len: lho + lfh_len + e['csize']]

        pad = 0
        if e['method'] == 0:  # STORED -> data must be aligned
            data_off = new_lho + lfh_len
            pad = (-data_off) % align
            if pad:
                struct.pack_into('<H', lfh, 28, elen + pad)  # extra len

        e['new_lho'] = new_lho
        out += lfh
        if pad:
            out += b'\x00' * pad
        out += payload

    # --- rebuild central directory with patched local header offsets ---
    new_cd_off = len(out)
    for e in entries:
        rec = bytearray(data[e['cd']: e['cd'] + 46 + e['nlen'] + e['elen'] + e['clen']])
        struct.pack_into('<I', rec, 42, e['new_lho'])
        out += rec

    new_cd_size = len(out) - new_cd_off

    # --- rewrite EOCD ---
    eocd_rec = bytearray(data[eocd:])
    struct.pack_into('<II', eocd_rec, 12, new_cd_size, new_cd_off)
    out += eocd_rec

    open(dst, 'wb').write(out)
    return True


def verify(path, align=4):
    z = zipfile.ZipFile(path)
    bad = []
    with open(path, 'rb') as f:
        raw = f.read()
    for zi in z.infolist():
        if zi.compress_type == 0:
            # recompute data offset from the local header
            lho = zi.header_offset
            nlen, elen = struct.unpack_from('<HH', raw, lho + 26)
            doff = lho + 30 + nlen + elen
            if doff % align:
                bad.append((zi.filename, doff))
    return bad


if __name__ == '__main__':
    if len(sys.argv) < 3:
        print(__doc__)
        raise SystemExit(2)
    a = int(sys.argv[3]) if len(sys.argv) > 3 else 4
    align_apk(sys.argv[1], sys.argv[2], a)
    bad = verify(sys.argv[2], a)
    print('zipalign: ok' if not bad else 'zipalign: %d unaligned' % len(bad))
    for n, o in bad[:10]:
        print('  ', n, o)
    raise SystemExit(0 if not bad else 1)
