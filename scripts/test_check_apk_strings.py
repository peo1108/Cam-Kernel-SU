#!/usr/bin/env python3
"""Tests for check_apk_strings.py: python3 -m unittest scripts/test_check_apk_strings.py"""

import gzip
import io
import random
import struct
import sys
import tempfile
import unittest
import zipfile
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_apk_strings import PATTERN, deflate_streams, main, scan  # noqa: E402

# What the strings of a binary look like around a name.
STRINGS = b"\0".join([b"/proc/self/fd", b"failed to open", b"usage: camd [options]", b"/data/adb/modules"] * 8)
# Random bytes like compressed data (seeded: the same bytes every run).
NOISE = random.Random(1).randbytes(4096)
# Binary data that looks compressed nowhere: numbers of a table.
TABLE = bytes(range(64)) * 64


def found(data):
    """(offset, text) of every name in data, and of every reason it could not be read."""
    return [(offset, text) for _, offset, text in scanned(data)]


def scanned(data):
    found_hits = []
    scan("file", data, found_hits)
    return found_hits


def hits(data):
    return [text for offset, text in found(data) if offset is not None]


def unreadable(data):
    return [text for offset, text in found(data) if offset is None]


# --- files as the formats store them ------------------------------------------------------------


def uleb128(n):
    out = bytearray()
    while True:
        out.append(n & 0x7F | (0x80 if n > 0x7F else 0))
        n >>= 7
        if not n:
            return bytes(out)


def dex_raw(*strings):
    """A DEX file with these strings, already in modified UTF-8, in its string pool."""
    data = bytearray(b"dex\n035\0" + bytes(0x70 - 8))
    table = len(data)
    data += bytes(4 * len(strings))
    struct.pack_into("<II", data, 0x38, len(strings), table)
    for i, s in enumerate(strings):
        struct.pack_into("<I", data, table + 4 * i, len(data))
        data += uleb128(len(s)) + s + b"\0"
    return bytes(data)


def dex(*strings):
    """A DEX file with these strings in its string pool."""
    return dex_raw(*(text.encode() for text in strings))


def utf8_length(n):
    return bytes([n]) if n < 0x80 else bytes([0x80 | n >> 8, n & 0xFF])


def utf16_length(n):
    return struct.pack("<H", n) if n < 0x8000 else struct.pack("<HH", 0x8000 | n >> 16, n & 0xFFFF)


def string_pool(strings, utf8=False):
    offsets, blob = [], bytearray()
    for s in strings:
        offsets.append(len(blob))
        if utf8:
            raw = s.encode()
            blob += utf8_length(len(s)) + utf8_length(len(raw)) + raw + b"\0"
        else:
            blob += utf16_length(len(s)) + s.encode("utf-16-le") + b"\0\0"
    blob += bytes(-len(blob) % 4)
    start = 28 + 4 * len(strings)
    header = struct.pack("<HHIIIIII", 1, 28, start + len(blob), len(strings), 0, 0x100 if utf8 else 0, start, 0)
    return header + b"".join(struct.pack("<I", o) for o in offsets) + blob


def binary_xml(*strings, utf8=False):
    """A compiled XML file (AndroidManifest.xml, res/*.xml) with these strings in its pool."""
    pool = string_pool(strings, utf8)
    return struct.pack("<HHI", 3, 8, 8 + len(pool)) + pool


def utf16_field(text, size):
    return text.encode("utf-16-le").ljust(size, b"\0")


def resource_table(package, *strings, children=b""):
    """resources.arsc with one package (children inside it) and these strings in the global pool."""
    header = struct.pack("<HHII", 0x200, 288, 288 + len(children), 0x7F) + utf16_field(package, 256) + bytes(20)
    body = string_pool(strings) + header + children
    return struct.pack("<HHII", 2, 12, 12 + len(body), 1) + body


def library_chunk(package):
    return struct.pack("<HHII", 0x203, 12, 12 + 260, 1) + struct.pack("<I", 2) + utf16_field(package, 256)


def overlayable_chunk(name, actor):
    return struct.pack("<HHI", 0x204, 1032, 1032) + utf16_field(name, 512) + utf16_field(actor, 512)


def elf(dynstr=b"\0", rodata=b"", text=b"", bits=64, order="<"):
    """An ELF file with these sections."""
    names = b"\0.dynstr\0.rodata\0.text\0.shstrtab\0"
    header, entry = (64, "IIQQQQIIQQ") if bits == 64 else (52, "IIIIIIIIII")
    data = bytearray(header)
    sections = []
    for name, kind, flags, content in ((1, 3, 2, dynstr), (9, 1, 2, rodata), (17, 1, 6, text), (23, 3, 0, names)):
        sections.append(struct.pack(order + entry, name, kind, flags, 0, len(data), len(content), 0, 0, 1, 0))
        data += content
    shoff = len(data)
    size = len(sections[0])
    data += bytes(size) + b"".join(sections)
    data[:7] = b"\x7fELF" + bytes([2 if bits == 64 else 1, 1 if order == "<" else 2, 1])
    if bits == 64:
        struct.pack_into(order + "Q", data, 0x28, shoff)
        struct.pack_into(order + "HHH", data, 0x3A, size, len(sections) + 1, len(sections))
    else:
        struct.pack_into(order + "I", data, 0x20, shoff)
        struct.pack_into(order + "HHH", data, 0x2E, size, len(sections) + 1, len(sections))
    return bytes(data)


def deflate(data):
    c = zlib.compressobj(9, zlib.DEFLATED, -15)
    return c.compress(data) + c.flush()


def stored(data, final=True):
    """data in one stored deflate block, kept as it is."""
    return bytes([1 if final else 0]) + struct.pack("<HH", len(data), len(data) ^ 0xFFFF) + data


def png(*chunks):
    out = b"\x89PNG\r\n\x1a\n"
    for kind, body in chunks:
        out += struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))
    return out


def webp(*chunks):
    body = b"WEBP" + b"".join(kind + struct.pack("<I", len(d)) + d + bytes(len(d) & 1) for kind, d in chunks)
    return b"RIFF" + struct.pack("<I", len(body)) + body


def font(*names, base=0, glyphs=NOISE, outlines=b"glyf"):
    """A TrueType font whose name table holds these names in UTF-16, as Windows fonts do; base is
    where the font starts in its file."""
    strings = b"".join(n.encode("utf-16-be") for n in names)
    records, pos = b"", 0
    for i, n in enumerate(names):
        size = len(n.encode("utf-16-be"))
        records += struct.pack(">6H", 3, 1, 0x409, i, size, pos)
        pos += size
    table = struct.pack(">3H", 0, len(names), 6 + len(records)) + records + strings
    directory = struct.pack(">IHHHH", 0x00010000, 2, 0, 0, 0)
    directory += struct.pack(">4sIII", outlines, 0, base + 44, len(glyphs))
    directory += struct.pack(">4sIII", b"name", 0, base + 44 + len(glyphs), len(table))
    return directory + glyphs + table


def cff_index(*items):
    if not items:
        return bytes(2)
    offsets = [1]
    for item in items:
        offsets.append(offsets[-1] + len(item))
    return struct.pack(">HB", len(items), 4) + b"".join(struct.pack(">I", o) for o in offsets) + b"".join(items)


def cff(name, string, outlines):
    """A CFF table: header, Name, Top DICT, String and Global Subr INDEX, then the outlines."""
    return bytes([1, 0, 4, 4]) + cff_index(name) + cff_index() + cff_index(string) + cff_index() + outlines


# Fixed Huffman codes of the literals (RFC 1951 3.2.6): (bits, code, byte)
LITERALS = [(8, 0x30 + v, v) for v in range(144)] + [(9, 0x190 + v - 144, v) for v in range(144, 256)]


def code_bits(length, code):
    return [code >> (length - 1 - j) & 1 for j in range(length)]  # Huffman codes go in MSB first


def huffman_spelling(target, at=7, plain_tail=b""):
    """A compressed ELF file (one block of literals) whose compressed bytes at offset at read
    target, as three bytes of camd's compressed payload once read "ksU"; and the file itself,
    which ends in plain_tail."""
    fixed = {(at + i) * 8 + b: byte >> b & 1 for i, byte in enumerate(target) for b in range(8)}
    end = (at + len(target)) * 8
    dead = set()

    def walk(pos):  # literals from bit pos on whose codes give the fixed bits
        if pos >= end:
            return []
        if pos in dead:
            return None
        for literal in LITERALS:
            bits = code_bits(*literal[:2])
            if all(fixed.get(pos + j, bit) == bit for j, bit in enumerate(bits)):
                rest = walk(pos + len(bits))
                if rest is not None:
                    return [literal] + rest
        dead.add(pos)
        return None

    head = [LITERALS[v] for v in b"\x7fELF"]
    tail = [LITERALS[v] for v in random.Random(2).randbytes(5000) + plain_tail]
    literals = head + walk(3 + 8 * len(head)) + tail
    bits = [1, 1, 0]  # last block, fixed Huffman codes
    for length, code, _ in literals:
        bits += code_bits(length, code)
    bits += [0] * 7  # end of block
    bits += [0] * (-len(bits) % 8)
    stream = bytes(sum(bit << j for j, bit in enumerate(bits[i : i + 8])) for i in range(0, len(bits), 8))
    return stream, bytes(value for _, _, value in literals)


# A kernel module as camd carries it: an ELF file that keeps its kernel names, compressed.
KO = elf(dynstr=b"\0ksu_handle_execve\0", rodata=b"KernelSU\0[ksu_driver]\0" + NOISE + bytes(8192))
PAYLOAD = deflate(KO)


def keeping(text):
    """A compressed ELF file that keeps text as it is, in a stored block between Huffman blocks."""
    c = zlib.compressobj(9, zlib.DEFLATED, -15)
    head = c.compress(KO[:64]) + c.flush(zlib.Z_FULL_FLUSH)
    return head + stored(text, final=False) + deflate(KO[64:])


def spans(data):
    return [(start, end) for start, end, _ in deflate_streams(data, 0, len(data))]


# --- tests --------------------------------------------------------------------------------------


class PatternTest(unittest.TestCase):
    def test_finds_names(self):
        for name in (
            "[ksu_driver]",
            "KernelSU",
            "KernelSu",
            "kernelsu.ko",
            "kernel_su_x",
            "me.weishu.kernelsu",
            "KSU_MODULE",
            "ksud",
            "u:r:ksu:s0",
            "libksud.so",
            "lib/arm64-v8a/libksud.so",
            "isKsuEnabled",
            "getKsuVersion",
            "Java_cam_caesar_vn_CamNative_isKsuEnabled",
            "LKsuService;",  # DEX type names in the default package
            "LKSUService;",
            "Lksu/Foo;",
            "STANDALONEKSUtrue",
            "ENABLEKSU",
            "Magisk patched imageksu_config",  # Rust literals packed with no separator
            "fooksuconfig",
            "fixappKSU_MODULE",
            "lost bufKernelSUunmarked",
            "kernelsumodule",
            "KERNEL_SU_VERSION",
            "/data/adb/kSU",  # any case at the start of a word
            "KsU",
            "KSuWebUI",
            "KernelSUPermission denied",  # Rust literals packed with no separator
            "KernelSUSpoof",
            "KERNELsu",
            "KERNEL_su",
            "KERNELSu",
            "kERNELSU",
            "KSud",
            "kSud",
            "kernel_sucompat",
            "KernelSunext",
            "image is not patched by KernelSUNo compatible ramdisk",
            "KernelSUManager",
        ):
            self.assertTrue(PATTERN.search(name), name)

    def test_skips_words_that_only_contain_ksu(self):
        for word in (
            "checksum",
            "LockSupport",
            "zygisksu",
            "tasksuccess",
            "kSuper calls",
            "KSuper",
            "KernelSupport",
            "kernel_supported",
            "kernelsupport",
            "kernelsuspend",
            "KERNEL_SUSPEND",
            "KernelSUPPORT",
            "Kernel_SUPPORT",
            "\x4bsupport/v4/app/FragmentActivity",  # a length byte (75) before "support"
            "\x6bsuccess",
            "kernel_sum",
            "KernelSuccess",
        ):
            self.assertIsNone(PATTERN.search(word), word)


class BinaryTest(unittest.TestCase):
    def test_finds_names_among_strings(self):
        for name in (b"[ksu_driver]", b"KernelSU", b"kernelsu.ko", b"me.weishu.kernelsu", b"KSU_MODULE"):
            self.assertTrue(hits(STRINGS + b"\0" + name + b"\0" + STRINGS), name)

    def test_finds_a_short_c_string(self):
        self.assertTrue(hits(TABLE + b"\0ksud\0" + TABLE))
        self.assertTrue(hits(TABLE + b"\x01KSULKM1\0" + TABLE))  # NUL on one side only
        self.assertTrue(hits(elf(rodata=NOISE + b"\0ksud\0" + NOISE)))

    def test_skips_a_long_run_inside_compressed_data(self):
        # seen in the x86_64 camd of a CI build: "|b+E)ksU" failed the build; in a file of no
        # format, text inside compressed-looking data is left out
        self.assertEqual(hits(NOISE[:2048] + b"\xe6|b+E)ksU\xa3" + NOISE[2048:]), [])
        self.assertTrue(hits(NOISE + b"\0KernelSU\0" + TABLE))  # compressed-looking on one side only

    def test_skips_a_pointer_that_spells_a_name(self):
        # a pointer to 0x55534b: what libcamd's relocations hold once it is over 5.6 MB
        self.assertEqual(hits(STRINGS + struct.pack("<QQ", 0x10, 0x55534B) + STRINGS), [])

    def test_skips_names_in_base64(self):
        blob = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAksuW3n0pQ7zLm2Xb"  # a public key
        self.assertEqual(hits(dex("a", blob)), [])
        self.assertTrue(hits(dex("/data/adb/ksu/modules/abc123/system/bin/Tool")))  # a path
        self.assertTrue(hits(dex("isKsuEnabledForTheCurrentUserProfileNow")))  # no digits
        self.assertEqual(hits(dex("$r8$lambda$jKU7zCgIY4dU4e4n6j46ksuWMPM")), [])  # a hash in a synthetic name
        # names are made of words, even with digits and capitals
        self.assertTrue(hits(dex("Lme/weishu/kernelsu/ui/screen/SuperUserScreen2Kt;")))
        self.assertTrue(hits(dex("isKsuEnabledForUser0AndProfileTemplate")))
        self.assertTrue(hits(dex("getKsuV2ApiForUid0Ok")))  # short words, camelCase
        self.assertTrue(hits(elf(dynstr=b"\0_ZN7android12KsuProfileManager14getProfileForUidEi\0")))

    def test_finds_utf8_text_among_binary(self):
        self.assertTrue(hits(elf(rodata=STRINGS + "\0B\u1eadt ksu \u0111i\0".encode() + STRINGS)))

    def test_finds_utf16_text(self):
        self.assertTrue(hits("export KSU_MODULE=1\n".encode("utf-16-le")))
        self.assertTrue(hits(TABLE + "\0KernelSU\0".encode("utf-16-le") + TABLE))
        self.assertTrue(hits(TABLE + "\0KernelSU\0".encode("utf-16-be") + TABLE))
        self.assertTrue(hits(TABLE + "\0B\u1eadt ksu\u1ea1".encode("utf-16-le") + TABLE))  # among non-ASCII

    def test_utf16_text_is_reported_once(self):
        # read one byte off, UTF-16 in one byte order is UTF-16 in the other
        self.assertEqual(found(b"\x01\x02" + "\0KernelSU\0".encode("utf-16-le")), [(4, "KernelSU")])

    def test_a_name_at_the_very_end_of_a_file(self):
        self.assertTrue(hits(NOISE + b"\0KSU_MODULE"))
        self.assertTrue(hits(elf() + b"\0ksud"))

    def test_skips_a_table_of_numbers_read_as_utf16(self):
        # u16 values 107, 115, 117 ("ksu") among others: CJK when read as UTF-16, no text
        table = struct.pack("<8H", *[0x4E00 + 97 * i for i in range(8)])
        self.assertEqual(hits(elf(rodata=STRINGS + table + "ksu".encode("utf-16-le") + table + STRINGS)), [])

    def test_skips_a_short_run_in_random_bytes(self):
        self.assertEqual(hits(NOISE[:2048] + b"\xe6ksu\xa3" + NOISE[2048:]), [])

    def test_finds_a_name_in_a_text_file(self):
        self.assertTrue(hits(b"export KSU_MODULE=x\n"))
        self.assertTrue(hits("Caf\u00e9 ksu \u00e9t\u00e9".encode("cp1252")))  # 8-bit text
        self.assertTrue(hits("B\u1eadt ksu \u0111i\n\0".encode()))  # a stray NUL
        self.assertTrue(hits("T\u1ea3i v\u1ec1\nksu\nxong".encode()))  # short, among non-ASCII text
        self.assertTrue(hits("ksu".encode("utf-16")))
        self.assertTrue(hits(b"\xff\xfe\0KernelSU\0" + bytes(10)))  # a BOM, then no text

    def test_reports_where_the_name_is(self):
        self.assertEqual(found(b"hello\nexport KSU_MODULE=x\n"), [(13, "hello.export KSU_MODULE=x.")])
        self.assertEqual([o for o, _ in found(TABLE + b"\0ab ksu_driver\0" + TABLE)], [len(TABLE) + 4])


class CompressedTest(unittest.TestCase):
    def test_finds_the_compressed_file_exactly(self):
        data = NOISE + STRINGS + PAYLOAD + NOISE
        start = len(NOISE + STRINGS)
        self.assertEqual(spans(data), [(start, start + len(PAYLOAD))])

    def test_names_inside_a_compressed_file_are_left_out(self):
        self.assertEqual(hits(elf(rodata=STRINGS + PAYLOAD + STRINGS)), [])

    def test_a_name_the_compressed_bytes_spell_is_left_out(self):
        stream, plain = huffman_spelling(b"\0[ksu_driver]\0")
        self.assertEqual(zlib.decompressobj(-15).decompress(stream), plain)
        self.assertIn(b"\0[ksu_driver]\0", stream)
        self.assertNotIn(b"[ksu_driver]", plain)
        self.assertTrue(hits(STRINGS + stream[:200] + STRINGS))  # read as it is, it is a name
        self.assertEqual(hits(elf(rodata=STRINGS + stream + STRINGS)), [])

    def test_left_out_too_when_the_file_holds_the_same_name(self):
        # a .ko holds "ksu" hundreds of times; three compressed bytes spelling it are still noise
        stream, plain = huffman_spelling(b"\0ksu\0", plain_tail=b"\0ksu_handle_execve\0ksu\0")
        self.assertIn(b"\0ksu\0", stream)
        self.assertIn(b"\0ksu\0", plain)
        self.assertEqual(hits(elf(rodata=STRINGS + stream + STRINGS)), [])

    def test_a_name_right_next_to_a_compressed_file_is_found(self):
        self.assertTrue(hits(elf(rodata=NOISE + PAYLOAD + b"[ksu_driver]" + NOISE)))
        self.assertTrue(hits(elf(rodata=NOISE + b"[ksu_driver]" + PAYLOAD + NOISE)))

    def test_text_a_compressed_file_stores_as_it_is_is_found(self):
        text = b"\0[ksu_driver]\0" + bytes(64)
        mixed = keeping(text)
        self.assertEqual(zlib.decompressobj(-15).decompress(mixed), KO[:64] + text + KO[64:])
        data = elf(rodata=NOISE + mixed + NOISE)
        self.assertEqual(len(spans(data)), 1)
        self.assertTrue(hits(data))

    def test_a_name_right_after_a_stored_block_header_is_found(self):
        # LEN 0x900c makes NLEN end in "o", which joins the run: "o[ksu_driver]"
        mixed = keeping(b"[ksu_driver]" + random.Random(3).randbytes(0x9000))
        self.assertIn(b"o[ksu_driver]", mixed)
        self.assertTrue(hits(elf(rodata=NOISE + mixed + NOISE)))

    def test_a_stream_that_opens_with_a_stored_block_is_read_as_it_is(self):
        # random bytes now and then read as a stored block of any length; libflate writes none
        opening = stored(b"\0[ksu_driver]\0" + bytes(6000), final=False)
        data = STRINGS + opening + PAYLOAD + STRINGS
        start = len(STRINGS + opening)
        self.assertEqual(spans(data), [(start, start + len(PAYLOAD))])
        self.assertTrue(hits(elf(rodata=data)))

    def test_other_compressed_data_is_decompressed_and_read(self):
        script = b"export KSU=true\n" + random.Random(4).randbytes(6000).hex().encode()
        found_in = [name for name, _, _ in scanned(elf(rodata=STRINGS + deflate(script) + STRINGS))]
        self.assertEqual([name.split("@")[0] for name in found_in], ["file!deflate"])  # inside the stream
        self.assertTrue(hits(gzip.compress(b"echo KernelSU")))  # small: no deflate stream looked for
        named = io.BytesIO()
        with gzip.GzipFile(filename="kernelsu_module.tar", fileobj=named, mode="wb") as g:
            g.write(b"echo Cam")
        self.assertEqual(hits(named.getvalue()), ["kernelsu_module.tar"])  # the name in the header
        self.assertTrue(hits(zlib.compress(b"echo KernelSU")))
        self.assertEqual(hits(gzip.compress(b"echo Cam")), [])
        self.assertTrue(hits(gzip.compress(b"export KSU=1\n") + b"garbage!"))  # bytes after it
        self.assertTrue(hits(gzip.compress(b"echo Cam") + gzip.compress(b"echo KernelSU")))  # two members

    def test_a_stream_after_long_dense_data(self):
        dense = random.Random(5).randbytes(80 * 1024)  # no deflate: given up on, then tried again
        data = dense + TABLE + PAYLOAD + TABLE
        start = len(dense + TABLE)
        self.assertEqual(spans(data), [(start, start + len(PAYLOAD))])

    def test_a_stream_start_is_not_taken_a_byte_early(self):
        # "s" ends the strings before it and reads as a fixed Huffman block header: decoding
        # from there falls in step with the stream and ends where it ends
        stream, _ = huffman_spelling(b"\0ksu\0", plain_tail=b"\0ksu_handle_execve\0")
        data = STRINGS + stream + STRINGS
        self.assertEqual(spans(data), [(len(STRINGS), len(STRINGS) + len(stream))])

    def test_a_short_stream_is_read_as_it_is(self):
        c = zlib.compressobj(9, zlib.DEFLATED, -15)
        short = c.compress(KO[:64]) + c.flush(zlib.Z_FULL_FLUSH) + stored(b"\0KernelSU\0" + bytes(100))
        self.assertEqual(spans(elf(rodata=NOISE + short + NOISE)), [])
        self.assertTrue(hits(elf(rodata=NOISE + short + NOISE)))

    def test_compressed_data_is_left_out_of_any_binary_file(self):
        stream, _ = huffman_spelling(b"\0[ksu_driver]\0")
        self.assertEqual(hits(NOISE + stream + NOISE), [])

    def test_camd_payload_is_deflate(self):
        # the streams left out are raw deflate: rust-embed's default; zstd would be read as text
        camd = Path(__file__).resolve().parents[1] / "userspace" / "camd"
        self.assertNotIn("#[compression", (camd / "src" / "assets.rs").read_text(encoding="utf-8"))
        self.assertIn('"compression"', (camd / "Cargo.toml").read_text(encoding="utf-8"))


class FormatTest(unittest.TestCase):
    def test_dex_strings_however_short(self):
        self.assertEqual(len(hits(dex("ksu", "ksud", "weishu", "plain"))), 3)

    def test_dex_code_is_not_read(self):
        # numbers: read as text, bytecode spells a three-letter name in about one build in six
        self.assertEqual(hits(dex("a", "b") + b"\x18\0KSULKM1\0"), [])

    def test_dex_offsets_count_modified_utf8_bytes(self):
        modified = b"a\xc0\x80\xed\xa0\xbd\xed\xb8\x80 ksu"  # NUL and an emoji as DEX stores them
        data = dex_raw(modified)
        self.assertEqual([o for o, _ in found(data)], [data.index(b"ksu")])

    def test_unknown_dex_versions_are_reported(self):
        self.assertTrue(unreadable(b"dex\n041\0" + dex("ksu")[8:]))

    def test_dex_length_byte_is_not_part_of_a_string(self):
        text = "su" + "x" * 73  # 75 is "K": read as bytes this is "Ksuxxx"
        self.assertIn(b"\0Ksu", dex("a", text))
        self.assertEqual(hits(dex("a", text)), [])

    def test_binary_xml_in_utf16(self):
        data = binary_xml("http://schemas.android.com/apk/res/android", "scheme", "ksu")
        self.assertNotIn(b"ksu", data)
        self.assertEqual(found(data), [(data.index("ksu".encode("utf-16-le")), "ksu")])

    def test_binary_xml_in_utf8(self):
        self.assertEqual(hits(binary_xml("android", "ksu", utf8=True)), ["ksu"])

    def test_long_strings(self):
        context = "x" * 23 + "/ksu"
        self.assertEqual(hits(binary_xml("x" * 0x8000 + "/ksu")), [context])  # length in two units
        self.assertEqual(hits(binary_xml("x" * 200 + "/ksu", "b", utf8=True)), [context])  # in two bytes

    def test_utf8_pool_length_bytes_are_not_part_of_a_string(self):
        text = "su" + "\u0103" * 36 + "x"  # 39 characters ("'"), 75 bytes ("K")
        data = binary_xml(text, utf8=True)
        self.assertIn(b"'Ksu", data)
        self.assertEqual(hits(data), [])

    def test_resource_table_package_names(self):
        self.assertTrue(hits(resource_table("me.weishu.kernelsu", "app_name")))
        self.assertEqual(hits(resource_table("cam.caesar.vn", "app_name")), [])
        self.assertTrue(hits(resource_table("cam.caesar.vn", "a", children=library_chunk("me.weishu.kernelsu"))))
        self.assertTrue(hits(resource_table("cam.caesar.vn", "a", children=overlayable_chunk("KernelSU", "x"))))
        self.assertTrue(hits(resource_table("cam.caesar.vn", "a", children=overlayable_chunk("x", "ksu"))))

    def test_resource_fields_stay_in_their_chunk(self):
        library = bytearray(library_chunk("cam"))
        struct.pack_into("<I", library, 8, 2)  # two entries said, one there
        self.assertTrue(unreadable(resource_table("cam.caesar.vn", "a", children=bytes(library) + library_chunk("x"))))

    def test_elf_string_tables(self):
        self.assertTrue(hits(elf(dynstr=b"\0Java_cam_caesar_vn_CamNative_isKsuEnabled\0")))
        self.assertTrue(hits(elf(dynstr=b"\0libc.so\0libksud.so\0")))
        self.assertEqual(hits(elf(dynstr=b"\0checksum\0LockSupport\0")), [])

    def test_elf_32_bit_big_endian(self):
        self.assertTrue(hits(elf(dynstr=b"\0libksud.so\0", bits=32, order=">")))
        self.assertEqual(hits(elf(dynstr=b"\0libc.so\0", bits=32, order=">")), [])

    def test_elf_code(self):
        # long runs only: next to a NUL, instructions spell four-letter runs too often
        self.assertTrue(hits(elf(text=NOISE + b"H\xb9KernelSUH\x89" + NOISE)))  # an 8-byte immediate
        self.assertEqual(hits(elf(text=NOISE + b"H\xb9KSULKM1\0" + NOISE)), [])

    def test_elf_bytes_outside_sections(self):
        self.assertTrue(hits(elf() + b"\0KernelSU\0"))

    def test_a_nul_in_the_section_before_counts(self):
        self.assertTrue(hits(elf(dynstr=b"\0abc\0", rodata=b"ksud\x01" + NOISE)))

    def test_images(self):
        header = (b"IHDR", bytes(13))
        self.assertTrue(hits(png(header, (b"tEXt", b"Software\0KernelSU"), (b"IEND", b""))))
        self.assertEqual(hits(png(header, (b"IDAT", NOISE + b"\0KernelSU\0"), (b"IEND", b""))), [])  # pixels
        self.assertTrue(hits(png(header, (b"IEND", b"")) + b"\0KernelSU\0"))  # bytes after the image
        self.assertTrue(hits(webp((b"VP8 ", NOISE), (b"XMP ", b"<x>KernelSU</x>"))))
        self.assertEqual(hits(webp((b"VP8L", NOISE + b"\0KernelSU\0"))), [])
        self.assertTrue(unreadable(png(header)[:-6]))

    def test_jpeg_and_gif(self):
        def segment(marker, body):
            return bytes([0xFF, marker]) + struct.pack(">H", len(body) + 2) + body

        scan_data = NOISE.replace(b"\xff", b"\xfe") + b"\0KernelSU\0"  # no marker inside
        jpeg = b"\xff\xd8" + segment(0xFE, b"made with KernelSU") + segment(0xDA, bytes(6)) + scan_data + b"\xff\xd9"
        self.assertEqual(hits(jpeg), ["made with KernelSU"])  # the comment, not the scan data

        def sub_blocks(body):
            return b"".join(bytes([len(body[i : i + 255])]) + body[i : i + 255] for i in range(0, len(body), 255)) + b"\0"

        gif = b"GIF89a" + bytes(7) + b"\x21\xfe" + sub_blocks(b"made with KernelSU")
        gif += b"\x2c" + bytes(9) + b"\x08" + sub_blocks(NOISE + b"\0KernelSU\0") + b"\x3b"
        self.assertEqual(hits(gif), ["made with KernelSU"])
        self.assertTrue(unreadable(gif[:-30]))

    def test_compressed_png_text(self):
        header = (b"IHDR", bytes(13))
        self.assertTrue(hits(png(header, (b"zTXt", b"Comment\0\0" + zlib.compress(b"Made with KernelSU")))))
        itxt = b"Comment\0\1\0en\0\0" + zlib.compress("Made with KernelSU".encode())
        self.assertTrue(hits(png(header, (b"iTXt", itxt))))
        self.assertTrue(hits(png(header, (b"iCCP", b"icc\0\0" + zlib.compress(b"desc\0KernelSU profile")))))
        self.assertEqual(hits(png(header, (b"zTXt", b"Comment\0\0" + zlib.compress(b"Made with Cam")))), [])

    def test_font_names(self):
        self.assertTrue(hits(font("Cam Sans", "KernelSU Sans")))
        self.assertEqual(hits(font("Cam Sans")), [])
        self.assertEqual(hits(font("Cam Sans", glyphs=NOISE + b"\0KernelSU\0")), [])  # outlines
        outlines = cff(b"Cam-Regular", b"Copyright Cam", NOISE + b"\0KernelSU\0")
        self.assertEqual(hits(font("Cam Sans", glyphs=outlines, outlines=b"CFF ")), [])
        named = cff(b"KernelSU-Regular", b"Copyright Cam", NOISE)
        self.assertEqual(hits(font("Cam Sans", glyphs=named, outlines=b"CFF ")), ["KernelSU-Regular"])
        self.assertEqual(len(hits(font("Cam Sans", "KernelSU Sans"))), 1)
        self.assertTrue(hits(font("Cam Sans") + b"\0KernelSU\0"))  # bytes no table claims
        collection = b"ttcf" + struct.pack(">III", 0x00010000, 1, 16) + font("KernelSU Sans", base=16)
        self.assertTrue(hits(collection))

    def test_a_broken_file_is_reported_and_still_read(self):
        broken = b"dex\n035\0" + b"\0KernelSU\0" * 20
        self.assertTrue(unreadable(broken))
        self.assertTrue(hits(broken))
        self.assertTrue(unreadable(binary_xml("ksu")[:-8]))
        self.assertTrue(unreadable(b"\x7fELF\2\1\1" + bytes(9)))
        huge = bytearray(elf(dynstr=b"\0KernelSU\0"))
        struct.pack_into("<Q", huge, 0x28, 1 << 63)  # section headers far past any file
        self.assertTrue(unreadable(bytes(huge)))


class MainTest(unittest.TestCase):
    def apk(self, directory, entries, signing_block=None, comment=b""):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            for name, data in entries.items():
                z.writestr(name, data)
            z.comment = comment
        data = buf.getvalue()
        if signing_block is not None:
            data = with_signing_block(data, signing_block)
        path = Path(directory) / "a.apk"
        path.write_bytes(data)
        return str(path)

    def test_status_follows_what_it_finds(self):
        with tempfile.TemporaryDirectory() as d:
            clean = self.apk(d, {"classes.dex": dex("cam.caesar.vn"), "lib/libcamd.so": elf(rodata=PAYLOAD)})
            self.assertEqual(main([clean]), 0)
            dirty = self.apk(d, {"classes.dex": dex("cam.caesar.vn", "ksu")})
            self.assertEqual(main([dirty]), 1)
            broken = self.apk(d, {"classes.dex": dex("cam.caesar.vn")[:0x40]})
            self.assertEqual(main([broken]), 1)

    def test_entry_names_and_comments(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(main([self.apk(d, {"assets/ksu_init.sh": b"echo\n"})]), 1)
            self.assertEqual(main([self.apk(d, {"res/raw/kernelsu_x": b""})]), 1)
            self.assertEqual(main([self.apk(d, {"a": b""}, comment=b"built for KernelSU")]), 1)
            info = zipfile.ZipInfo("a")
            info.comment = b"from ksu"
            self.assertEqual(main([self.apk(d, {info: b""})]), 1)

    def test_signing_block(self):
        cert = b"\x30\x82\x01\x0a\x06\x03\x55\x04\x03\x0c\x11KernelSU PR Build" + NOISE[:512]
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(main([self.apk(d, {"classes.dex": dex("a")}, signing_block=cert)]), 1)
            clean = cert.replace(b"KernelSU", b"Cam  Kit")
            self.assertEqual(main([self.apk(d, {"classes.dex": dex("a")}, signing_block=clean)]), 0)

    def test_nested_archives(self):
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("classes.dex", dex("ksu"))
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(main([self.apk(d, {"assets/x.jar": jar.getvalue()})]), 1)

    def test_a_broken_archive_is_reported(self):
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("classes.dex", dex("cam") * 50)
        data = jar.getvalue()
        self.assertTrue(unreadable(data[: data.rfind(b"PK\x01\x02")]))  # no central directory
        bad = bytearray(data)
        bad[60] ^= 0xFF  # inside the compressed entry
        self.assertTrue(unreadable(bytes(bad)))

    def test_an_archive_with_bytes_before_it(self):
        plain = io.BytesIO()
        with zipfile.ZipFile(plain, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("a.sh", b"export KSU=1\n" * 20)
        self.assertTrue(hits(bytes(16) + plain.getvalue()))
        clean = io.BytesIO()
        with zipfile.ZipFile(clean, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("a.sh", b"export CAM=1\n")
        self.assertEqual(hits(bytes(16) + clean.getvalue()), [])
        self.assertTrue(hits(b"\0KernelSU\0" + clean.getvalue()))  # the bytes before it are read

    def test_bytes_no_record_takes(self):
        plain = io.BytesIO()
        with zipfile.ZipFile(plain, "w") as z:
            z.writestr("a", b"echo Cam")
            z.writestr("b", b"echo Cam")
        data = plain.getvalue()
        self.assertEqual(hits(data), [])
        self.assertTrue(hits(data + b"\0KernelSU trailing\0"))  # after the end of the archive
        info = zipfile.ZipInfo("a")
        info.comment = b"from ksu"
        commented = io.BytesIO()
        with zipfile.ZipFile(commented, "w") as z:
            z.writestr(info, b"x")
        data = commented.getvalue()
        self.assertEqual([o for o, _ in found(data)], [data.rindex(b"from ksu") + 5])  # in the directory

    def test_odd_archives_are_reported_not_crashed_on(self):
        plain = io.BytesIO()
        with zipfile.ZipFile(plain, "w") as z:
            z.writestr("abc", b"x")
        data = bytearray(plain.getvalue())
        directory = data.rfind(b"PK\x01\x02")
        data[directory + 8] |= 0x08  # bit 11 of the flags: names are UTF-8
        data[directory + 9] |= 0x08
        data[directory + 46] = 0xFF  # but this one is not
        self.assertTrue(unreadable(bytes(data)))
        bz = io.BytesIO()
        with zipfile.ZipFile(bz, "w", zipfile.ZIP_BZIP2) as z:
            z.writestr("a", b"cam" * 200)
        broken = bytearray(bz.getvalue())
        broken[45:50] = bytes(5)  # the compressed data
        self.assertTrue(unreadable(bytes(broken)))

    def test_images_are_read(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(main([self.apk(d, {"res/a.png": NOISE + b"tEXtSoftware\0KernelSU\0"})]), 1)
            self.assertEqual(main([self.apk(d, {"res/a.png": NOISE})]), 0)


def with_signing_block(apk, value):
    """apk with an APK Signing Block holding value, right before the central directory."""
    eocd = apk.rfind(b"PK\x05\x06")
    (directory,) = struct.unpack_from("<I", apk, eocd + 16)
    pair = struct.pack("<QI", 4 + len(value), 0x7109871A) + value
    size = len(pair) + 8 + 16
    block = struct.pack("<Q", size) + pair + struct.pack("<Q", size) + b"APK Sig Block 42"
    out = bytearray(apk[:directory] + block + apk[directory:])
    struct.pack_into("<I", out, eocd + len(block) + 16, directory + len(block))
    return bytes(out)


if __name__ == "__main__":
    unittest.main()
