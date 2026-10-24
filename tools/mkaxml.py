#!/usr/bin/env python3
"""生成 Android 二进制 XML (AXML)，绕开只能在 x86_64 上运行的 aapt2。"""
import struct

TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_BOOLEAN = 0x12
NO_ENTRY = 0xFFFFFFFF


def uleb(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


class Pool:
    def __init__(self):
        self.items = []
        self.idx = {}

    def add(self, s):
        if s in self.idx:
            return self.idx[s]
        self.idx[s] = len(self.items)
        self.items.append(s)
        return self.idx[s]

    def build(self):
        data = bytearray()
        offsets = []
        for s in self.items:
            offsets.append(len(data))
            b = s.encode('utf-8')
            data += uleb(len(s)) + uleb(len(b)) + b + b'\x00'
        while len(data) % 4:
            data += b'\x00'
        strings_start = 28 + 4 * len(self.items)
        size = strings_start + len(data)
        out = struct.pack('<HHI', 0x0001, 28, size)
        out += struct.pack('<IIIII', len(self.items), 0, 0x100, strings_start, 0)
        for o in offsets:
            out += struct.pack('<I', o)
        return out + bytes(data)


def start_ns(prefix, uri):
    return struct.pack('<HHIIIII', 0x0100, 16, 24, 1, NO_ENTRY, prefix, uri)


def end_ns(prefix, uri):
    return struct.pack('<HHIIIII', 0x0101, 16, 24, 1, NO_ENTRY, prefix, uri)


def start_element(ns, name, attrs, line=1):
    """attrs: list of (ns_idx, name_idx, raw_idx, data_type, data)."""
    body = struct.pack('<II', ns, name)
    body += struct.pack('<HHHHHH', 20, 20, len(attrs), 0, 0, 0)
    for (ans, aname, raw, dtype, data) in attrs:
        body += struct.pack('<III', ans, aname, raw)
        body += struct.pack('<HBBI', 8, 0, dtype, data)
    size = 16 + len(body)
    return struct.pack('<HHIII', 0x0102, 16, size, line, NO_ENTRY) + body


def end_element(ns, name, line=1):
    return struct.pack('<HHIIIII', 0x0103, 16, 24, line, NO_ENTRY, ns, name)


def build():
    p = Pool()

    # --- 属性名必须连续放在池子开头，ResourceMap 按索引对应 ---
    A_VERSION_CODE = p.add('versionCode')      # 0
    A_VERSION_NAME = p.add('versionName')      # 1
    A_LABEL = p.add('label')                   # 2
    A_NAME = p.add('name')                     # 3
    A_VALUE = p.add('value')                   # 4
    A_HAS_CODE = p.add('hasCode')              # 5
    ATTR_IDS = [0x0101021b, 0x0101021c, 0x01010001, 0x01010003, 0x01010024, 0x0101000c]

    S_PACKAGE = p.add('package')
    S_MANIFEST = p.add('manifest')
    S_APP = p.add('application')
    S_META = p.add('meta-data')
    S_PREFIX = p.add('android')
    S_URI = p.add('http://schemas.android.com/apk/res/android')
    S_PKG = p.add('com.jcy.doubletorch')
    S_VER = p.add('1.2.0')
    S_LABEL = p.add('DoubleTapTorch')
    S_TRUE = p.add('true')
    S_MINVER = p.add('93')
    S_X_MODULE = p.add('xposedmodule')
    S_X_DESC = p.add('xposeddescription')
    S_X_MIN = p.add('xposedminversion')
    S_DESC = p.add('双击电源键开关手电筒（替代卡包）')

    U = S_URI
    NONE = NO_ENTRY

    body = bytearray()
    body += start_ns(S_PREFIX, S_URI)

    # <manifest package=... android:versionCode=1 android:versionName="1.0">
    body += start_element(NONE, S_MANIFEST, [
        (NONE, S_PACKAGE, S_PKG, TYPE_STRING, S_PKG),
        (U, A_VERSION_CODE, NONE, TYPE_INT_DEC, 120),
        (U, A_VERSION_NAME, S_VER, TYPE_STRING, S_VER),
    ])
    # <application android:label="DoubleTapTorch" android:hasCode=true>
    body += start_element(NONE, S_APP, [
        (U, A_LABEL, S_LABEL, TYPE_STRING, S_LABEL),
        (U, A_HAS_CODE, NONE, TYPE_INT_BOOLEAN, 0xFFFFFFFF),
    ])

    for name_idx, val_idx in (
        (S_X_MODULE, S_TRUE),
        (S_X_DESC, S_DESC),
        (S_X_MIN, S_MINVER),
    ):
        body += start_element(NONE, S_META, [
            (U, A_NAME, name_idx, TYPE_STRING, name_idx),
            (U, A_VALUE, val_idx, TYPE_STRING, val_idx),
        ])
        body += end_element(NONE, S_META)

    body += end_element(NONE, S_APP)
    body += end_element(NONE, S_MANIFEST)
    body += end_ns(S_PREFIX, S_URI)

    pool = p.build()
    m = struct.pack('<HHI', 0x0180, 8, 8 + 4 * len(ATTR_IDS))
    for i in ATTR_IDS:
        m += struct.pack('<I', i)

    chunks = pool + m + bytes(body)
    total = 8 + len(chunks)
    return struct.pack('<HHI', 0x0003, 8, total) + chunks


if __name__ == '__main__':
    import sys
    data = build()
    with open(sys.argv[1], 'wb') as f:
        f.write(data)
    print('wrote', sys.argv[1], len(data), 'bytes')
