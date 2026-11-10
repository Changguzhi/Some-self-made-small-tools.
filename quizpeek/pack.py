"""把 classes.dex 塞进 APK，并对未压缩条目做 4 字节对齐（替代 zipalign）。"""
import struct
import sys
import zipfile

src, dex_path, dst = sys.argv[1], sys.argv[2], sys.argv[3]


def padding_extra(offset, name_len, base_extra):
    """算出让数据起点 4 字节对齐的额外字段。"""
    base = offset + 30 + name_len + len(base_extra)
    need = (-base) % 4
    if need == 0:
        return b""
    need += 4  # 至少放得下一个 extra header，加 4 不破坏对齐
    body = need - 4
    return struct.pack("<HH", 0x9999, body) + b"\x00" * body


def add(zout, name, data, compress, date_time):
    info = zipfile.ZipInfo(name, date_time)
    info.compress_type = zipfile.ZIP_DEFLATED if compress else zipfile.ZIP_STORED
    info.external_attr = 0o644 << 16
    if not compress:
        info.extra = padding_extra(zout.fp.tell(), len(name.encode("utf-8")), b"")
    zout.writestr(info, data)


with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
    for item in zin.infolist():
        if item.filename == "classes.dex":
            continue
        data = zin.read(item.filename)
        stored = item.compress_type == zipfile.ZIP_STORED
        add(zout, item.filename, data, not stored, item.date_time)

    with open(dex_path, "rb") as f:
        add(zout, "classes.dex", f.read(), False, (2026, 1, 1, 0, 0, 0))

print("packed")
