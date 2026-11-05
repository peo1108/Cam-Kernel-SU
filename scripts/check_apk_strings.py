#!/usr/bin/env python3
"""Lists upstream (KernelSU) names left in a Manager APK, or in any files given.

Any app that can see the Manager can read its APK; a root checker scanning it must not find
"KernelSU", "ksu" or "weishu" (docs/CAM_CHANGES.md rows 85 and 90). Names that still have to
exist at runtime are built by camd's `hidden!` and the Manager's `Hidden.s`, so they never appear
as text.

    python3 scripts/check_apk_strings.py Cam_<version>_<code>-release.apk [more files...]

Exit status 1 when a name is found, or when a file that starts like one of the formats below
cannot be read as it. Each format is read the way it stores text, so the length bytes in front
of a string never join it:
- APK/ZIP: entry names and comments, the APK Signing Block, every entry (nested archives too,
  and an archive with bytes before it)
- DEX: the string pool (literals, class and member names), string by string; code and array
  data are numbers, read as text they spell three-letter names too often
- binary XML and resources.arsc: the string pools (UTF-8 or UTF-16) and the package names
- ELF: the string tables (exported and imported names, DT_NEEDED) string by string, all other
  bytes as text runs, code too (x86 code holds short strings as immediates)
- PNG, WebP, JPEG, GIF: everything but the compressed pixel data; compressed PNG text and ICC
  profiles decompressed. Other media (audio, textures) are read as any other file: should one
  ever spell a name in its compressed data, give it a reader too
- fonts: the names of the name table and of CFF, every other table but outlines, metrics and
  layout as text runs
- anything else: the whole file when it is text (UTF-8, UTF-16 with a BOM, 8-bit), else its text
  runs
A text run is printable text (ASCII, UTF-8 or UTF-16) MIN_TEXT_RUN characters long or more, or
MIN_C_STRING next to a NUL (C strings, immediates): three random bytes often spell a name, eight
rarely, and a pointer or an offset under 16 MB spells three at most. A name inside a long base64
blob (an encoded key or table) is left out too. Compressed data (raw deflate streams of
MIN_STREAM bytes or more, also in zlib and gzip data) is decompressed and read as a file inside
the file, but for compressed ELF files: camd carries caminit and the .ko compressed by
rust-embed, and those keep their kernel names (row 85). What such a stream keeps as it is
(stored blocks) is still read: a name there is in the decompressed data too, with the
STORED_CONTEXT bytes before or after it, which compressed bytes never are.
"""
import io
import re
import struct
import sys
import zipfile
import zlib

# English words after kernel(_): support, super, suspend, subsystem, success, such, suite, sure, sum...
SU_WORDS = "pp|pe|sp|bs|cc|ff|gg|rf|rv|ch|it|re|dd|mm|m(?![a-z])|n(?![a-z])"
# the same in capitals, as whole words: "KernelSUNo", "KernelSUManager" are camelCase
SU_CAPITAL_WORDS = "PP|PE|SP|BS|CC|FF|GG|RF|RV|CH|IT|RE|DD|MM|M(?![A-Za-z])|N(?![A-Za-z])"
PATTERN = re.compile(
    r"(?!)"  # (each branch below starts with "|")
    # a word: ksu, KSU_MODULE, [ksu_driver], libksud.so, DEX type names (Lksu/Foo;), in any case
    # but a "k" before a word that starts with "su": "kSuper", "Ksupport/v4/app" are what a length
    # byte (75 or 107) before "Super", "support" reads as; a letter before it keeps "checksum",
    # "LockSupport", "zygisksu" out
    rf"|(?<![A-Za-z])L?(?:lib)?(?![kK](?:Su[a-ce-z]|[sS]u(?:{SU_WORDS})))(?i:ksu)"
    # camelCase, and glued to the string before: Rust packs its literals with no separator
    r"|(?<=[A-Za-z])Ksu|(?<=[a-z])KSU|(?<=[A-Z])KSU(?![A-Z])"
    r"|ksu(?=[_d]|init|calls|ctl|config|webui|rc\b)|KSU(?=[_D])"
    # KernelSU however written, also glued to what follows ("KernelSUunmarked", "kernel_sucompat"),
    # but not an English word that starts with "su" (kernelsupport, KernelSUPPORT, KERNEL_SUSPEND,
    # kernel_supported, KernelSupport)
    rf"|(?i:kernel)_?(?:su(?!{SU_WORDS})|Su(?!{SU_WORDS})|SU(?!{SU_CAPITAL_WORDS})|sU)"
    r"|(?i:weishu)"
)
CONTEXT = 24
MIN_TEXT_RUN = 8
MIN_C_STRING = 4
TEXT_BYTE = frozenset(range(0x20, 0x7F)) | {0x09, 0x0A, 0x0D}
BASE64 = frozenset("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+")  # "/" splits paths
MIN_BASE64 = 20
WORD = re.compile(r"[a-z]{3,}")


def candidates(unit):
    """Bytes every name PATTERN finds holds, in ASCII or UTF-16, for a cheap first pass."""

    def word(text):
        chars = [b"[%s%s]" % (c.upper().encode(), c.lower().encode()) if c.isalpha() else c.encode() for c in text]
        return b"".join({"utf-8": c, "utf-16-le": c + b"\0", "utf-16-be": b"\0" + c}[unit] for c in chars)

    return re.compile(b"|".join([word("ksu"), word("weishu"), word("kernel") + b"(?:" + word("_") + b")?" + word("su")]))


RUNS = [(1, "utf-8", candidates("utf-8")), (2, "utf-16-le", candidates("utf-16-le")), (2, "utf-16-be", candidates("utf-16-be"))]

# Deflate streams are looked for where a block of BLOCK bytes has DISTINCT different values or
# more (compressed data about 160, text and code far fewer), from SEARCH bytes before it on.
# A stream counts when it opens with a Huffman block: rust-embed's libflate writes nothing else,
# and random bytes now and then read as a stored block (LEN and NLEN agree) of any length.
BLOCK = 256
DISTINCT = 140
SEARCH = 4096
MIN_STREAM = 4096  # tables and code now and then read as a short valid stream
RESYNC = 9
GIVE_UP = 256  # dense blocks (64 KB): camd's tables run to 64
STORED_CONTEXT = 8
DENSE_SIDE = 128  # random bytes: about 100 different values in 128
DENSE_DISTINCT = 88

# Bytes a decoder does not take stay in the text as surrogates, so offsets come back exact.
ERRORS = {"utf-8": "surrogateescape", "utf-16-le": "surrogatepass", "utf-16-be": "surrogatepass"}


def decode(raw, encoding):
    return raw.decode(encoding, ERRORS.get(encoding, "strict"))


def size_in(text, encoding):
    """How many bytes text takes in the file."""
    return len(text.encode(encoding, ERRORS.get(encoding, "strict")))


HUMP = re.compile(r"[A-Z][a-z]{2}")


def in_base64(text, start, end):
    """Whether text[start:end] lies in a base64 blob (a key, a table, a hash in a synthetic name):
    MIN_BASE64 letters, digits and "+" or more with digits, a tenth of them capitals and a tenth
    small letters at least, and neither words nor camelCase. Names have one or the other: three
    quarters of their small letters or more in runs of three ("weishu"), or two capitals in three
    or more starting one ("getKsuV2ApiForUid0"); random base64 a third, and a sixth."""
    while start > 0 and text[start - 1] in BASE64:
        start -= 1
    while end < len(text) and text[end] in BASE64:
        end += 1
    blob = text[start:end]
    if len(blob) < MIN_BASE64 or not any(c.isdigit() for c in blob):
        return False
    small = sum(c.islower() for c in blob)
    if sum(c.isupper() for c in blob) < len(blob) / 10 or small < len(blob) / 10:
        return False
    if sum(len(word) for word in WORD.findall(blob)) >= 0.6 * small:
        return False
    return len(HUMP.findall(blob)) < 0.5 * sum(c.isupper() for c in blob)


def find(name, pieces, hits):
    """pieces: (offset, text, encoding), the text being at offset in the file, in encoding."""
    for offset, text, encoding in pieces:
        done = 0
        for m in PATTERN.finditer(text):
            if in_base64(text, m.start(), m.end()):
                continue
            offset += size_in(text[done : m.start()], encoding)
            done = m.start()
            context = text[max(0, m.start() - CONTEXT) : m.end() + CONTEXT]
            hits.append((name, offset, "".join(c if " " <= c <= "~" else "." for c in context)))


# --- binary data -------------------------------------------------------------------------------


def deflate_stream(view, start, end):
    """(end, decompressed) of the raw deflate stream at view[start:end], or None."""
    if view[start] & 6 not in (2, 4):  # opens with a fixed or dynamic Huffman block
        return None
    d = zlib.decompressobj(-15)
    out = []
    pos, step = start, 256  # most offsets fail within a few bytes
    try:
        while pos < end and not d.eof:
            chunk = view[pos : min(end, pos + step)]
            out.append(d.decompress(chunk))
            pos += len(chunk)
            step = 65536
    except zlib.error:
        return None
    stop = pos - len(d.unused_data)
    return (stop, b"".join(out)) if d.eof and stop - start >= MIN_STREAM else None


def deflate_streams(data, start, end):
    """(start, end, decompressed) of every raw deflate stream in data[start:end]."""
    view = memoryview(data)
    streams = []
    tried = block = start  # offsets below tried were tried already
    failed = 0  # dense blocks in a row with no stream
    while block + BLOCK <= end:
        if len(set(view[block : block + BLOCK])) < DISTINCT:
            block += BLOCK
            failed = 0
            continue
        failed += 1
        if failed > GIVE_UP:  # compressed some other way: not tried offset by offset
            tried = block = block + BLOCK
            continue
        for s in range(max(tried, block - SEARCH), block + BLOCK):
            found = deflate_stream(view, s, end)
            if found:
                # A byte or two around the start of a stream can read as a block header whose
                # codes fall in step with the stream's own. Of the offsets that end there, the
                # one an ELF file starts at (camd's payload), else the last.
                stop = found[0]
                starts = [(s, found[1])]
                for x in range(s + 1, min(s + RESYNC, stop)):
                    other = deflate_stream(view, x, end)
                    if other and other[0] == stop:
                        starts.append((x, other[1]))
                elf = [st for st in starts if st[1][:4] == b"\x7fELF"]
                s, plain = elf[0] if elf else starts[-1]
                streams.append((s, stop, plain))
                tried = block = stop
                failed = 0
                break
        else:
            tried = block = block + BLOCK
    return streams


# Beyond ASCII, text runs take the letters of Latin scripts (Vietnamese too), combining marks and
# punctuation: random numbers read as UTF-16 fall in CJK a third of the time, in these rarely.
TEXT_RANGES = ((0xA0, 0x250), (0x300, 0x370), (0x1E00, 0x1F00), (0x2010, 0x2070))


def is_text_char(u):
    return u in TEXT_BYTE or any(lo <= u < hi for lo, hi in TEXT_RANGES)


def char_at(data, pos, end, unit, encoding):
    """Size of the text character at data[pos:end], 0 when there is none."""
    if pos + unit > end:
        return 0
    if unit == 2:
        u = data[pos] | data[pos + 1] << 8 if encoding == "utf-16-le" else data[pos] << 8 | data[pos + 1]
        return 2 if is_text_char(u) else 0
    b = data[pos]
    if b in TEXT_BYTE:
        return 1
    size = 2 if 0xC2 <= b <= 0xDF else 3 if 0xE0 <= b <= 0xEF else 0
    if not size or pos + size > end:
        return 0
    try:
        return size if is_text_char(ord(data[pos : pos + size].decode("utf-8"))) else 0
    except UnicodeDecodeError:
        return 0


def char_before(data, pos, start, unit, encoding):
    """Size of the text character that ends at data[pos], 0 when there is none after start."""
    for size in (1, 2, 3) if unit == 1 else (2,):
        if pos - size >= start and char_at(data, pos - size, pos, unit, encoding) == size:
            return size
    return 0


def dense_around(data, left, right):
    """Whether the run data[left:right] sits in compressed-looking data on both sides."""
    before, after = data[max(0, left - DENSE_SIDE) : left], data[right : right + DENSE_SIDE]
    return len(before) == len(after) == DENSE_SIDE and min(len(set(before)), len(set(after))) >= DENSE_DISTINCT


def text_runs(data, start, end, code=False, opaque=False):
    """Text runs in data[start:end] that hold a candidate name, as pieces. In code only long
    runs: instructions next to a NUL spell four-letter runs too often. In opaque data (a file of no
    format this reads) none inside compressed-looking data: zstd, encrypted or media bytes."""
    pieces, taken = [], []
    for unit, encoding, candidate in RUNS:
        after = start
        for m in candidate.finditer(data, start, end):
            if m.start() < after or (unit == 2 and any(a - 1 <= m.start() <= b for a, b in taken)):
                continue  # inside a run found already (UTF-16 text reads as both byte orders)
            left, right = m.start(), m.end()
            length = (right - left) // unit
            while size := char_before(data, left, start, unit, encoding):
                left -= size
                length += 1
            while size := char_at(data, right, end, unit, encoding):
                right += size
                length += 1
            after = right
            nul = (left >= unit and not any(data[left - unit : left])) or (
                right + unit <= len(data) and not any(data[right : right + unit])
            )
            if length < MIN_TEXT_RUN and (code or length < MIN_C_STRING or not nul):
                continue
            if opaque and dense_around(data, left, right):
                continue
            pieces.append((left, decode(data[left:right], encoding), encoding))
            if unit == 2:
                taken.append((left, right))
    return pieces


def stored_runs(data, start, end, plain):
    """Text runs of the deflate stream data[start:end] that it keeps as they are: a name in one
    is in the decompressed stream (plain) too, with STORED_CONTEXT bytes before or after it."""
    kept = []
    for piece in text_runs(data, start, end):
        offset, text, encoding = piece
        for m in PATTERN.finditer(text):
            a = offset + size_in(text[: m.start()], encoding)
            b = a + size_in(m.group(), encoding)
            before = data[a - STORED_CONTEXT : b] if a - STORED_CONTEXT >= start else None
            after = data[a : b + STORED_CONTEXT] if b + STORED_CONTEXT <= end else None
            if (before and before in plain) or (after and after in plain):
                kept.append(piece)
                break
    return kept


def binary_pieces(data, inner, start=0, end=None, code=False, opaque=False):
    """Text runs of data[start:end] (see text_runs). Deflate streams in it are decompressed into
    inner, as files inside the file; a compressed ELF file is left out, but for what it stores as
    it is."""
    end = len(data) if end is None else end
    pieces = []
    for s, e, plain in deflate_streams(data, start, end) + [(end, end, b"")]:
        pieces += text_runs(data, start, s, code, opaque)
        if s < e:
            if b"\x7fELF" in plain[:16]:  # a few bytes before it when the start is off by one
                pieces += stored_runs(data, s, e, plain)
            else:
                inner.append((f"deflate@{s:#x}", plain))
        start = e
    return pieces


def except_ranges(data, ranges, inner):
    """Text runs of data outside ranges ((start, end) pairs)."""
    pieces = []
    pos = 0
    for start, end in sorted(ranges):
        if start > pos:
            pieces += binary_pieces(data, inner, pos, start)
        pos = max(pos, end)
    return pieces + binary_pieces(data, inner, pos)


PRINTABLE = bytes(sorted(TEXT_BYTE))
LATIN1_TEXT = PRINTABLE + bytes(range(0xA0, 0x100))


def mostly_text(controls, size):
    """At most one control character in a hundred (a stray NUL, a form feed)."""
    return controls <= max(1, size // 100)


def text_pieces(data):
    """The whole file when it is text: UTF-16 with a BOM, UTF-8, or 8-bit text such as CP1252."""
    for bom, encoding in ((b"\xff\xfe", "utf-16-le"), (b"\xfe\xff", "utf-16-be")):
        if data.startswith(bom) and len(data) % 2 == 0:
            text = decode(data[2:], encoding)
            controls = sum(ord(c) < 0x20 and c not in "\t\n\r" for c in text)
            if mostly_text(controls, len(text)):
                return [(2, text, encoding)]
    try:
        text = data.decode("utf-8")
        if mostly_text(len(data.translate(None, PRINTABLE + bytes(range(0x80, 0x100)))), len(data)):
            return [(0, text, "utf-8")]
    except UnicodeDecodeError:
        if mostly_text(len(data.translate(None, LATIN1_TEXT)), len(data)):
            return [(0, data.decode("latin-1"), "latin-1")]
    return None


def gzip_header(data):
    """The extra field, file name and comment of a gzip header."""
    flags, pos, pieces = data[3], 10, []
    if flags & 4:  # FEXTRA
        (size,) = struct.unpack_from("<H", data, pos)
        pieces.append((pos + 2, decode(data[pos + 2 : pos + 2 + size], "latin-1"), "latin-1"))
        pos += 2 + size
    for flag in (8, 16):  # FNAME, FCOMMENT: NUL-terminated
        if flags & flag:
            nul = data.index(b"\0", pos)
            pieces.append((pos, decode(data[pos:nul], "latin-1"), "latin-1"))
            pos = nul + 1
    return pieces


def any_pieces(data):
    """A file of no format below: gzip or zlib data decompressed (as a file inside it; deflate
    streams are only looked for from MIN_STREAM bytes on), its text, or its text runs."""
    if data[:3] == b"\x1f\x8b\x08":
        d = zlib.decompressobj(31)
        try:
            plain = d.decompress(data)
            header = gzip_header(data)
        except (zlib.error, struct.error, IndexError, ValueError):
            pass  # not gzip after all
        else:
            if d.eof:  # what follows (another member, anything) is read as a file of its own
                return header, [("gzip", plain)] + ([("after gzip", d.unused_data)] if d.unused_data else [])
    if len(data) > 2 and data[0] & 0x0F == 8 and (data[0] << 8 | data[1]) % 31 == 0:
        d = zlib.decompressobj()
        try:
            plain = d.decompress(data)
        except zlib.error:
            pass  # not zlib after all
        else:
            if d.eof:
                inner = [("zlib", plain)]
                return binary_pieces(data, inner, len(data) - len(d.unused_data)), inner
    pieces = text_pieces(data)
    if pieces is not None:
        return pieces, []
    inner = []
    return binary_pieces(data, inner, opaque=True), inner


# --- DEX ---------------------------------------------------------------------------------------

DEX_VERSIONS = (b"035", b"037", b"038", b"039", b"040")  # 041 holds several DEX files: not read


def dex_pieces(data):
    if data[4:7] not in DEX_VERSIONS:
        raise ValueError(f"DEX version {data[4:7]!r} is not read")
    count, table = struct.unpack_from("<II", data, 0x38)
    pieces = []
    for i in range(count):
        (pos,) = struct.unpack_from("<I", data, table + 4 * i)
        while data[pos] & 0x80:  # uleb128 length in UTF-16 units
            pos += 1
        pos += 1
        nul = data.index(b"\0", pos)
        pieces.append((pos, decode(data[pos:nul], "utf-8"), "utf-8"))  # MUTF-8
    return pieces, []


# --- binary XML and resources.arsc ------------------------------------------------------------

RES_STRING_POOL = 0x0001
RES_TABLE = 0x0002
RES_XML = 0x0003
RES_TABLE_PACKAGE = 0x0200
RES_TABLE_LIBRARY = 0x0203
RES_TABLE_OVERLAYABLE = 0x0204
UTF8_FLAG = 0x100


def utf16z(data, pos, size, end):
    """A NUL-padded UTF-16 field of size bytes, such as a package name, in a chunk ending at end."""
    if pos + size > end:
        raise ValueError(f"field past the end of its chunk at {pos:#x}")
    return pos, decode(data[pos : pos + size], "utf-16-le").split("\0", 1)[0], "utf-16-le"


def string_pool(data, chunk, header_size, chunk_end):
    count, _, flags, strings = struct.unpack_from("<IIII", data, chunk + 8)
    pieces = []
    for i in range(count):
        (pos,) = struct.unpack_from("<I", data, chunk + header_size + 4 * i)
        pos += chunk + strings
        if flags & UTF8_FLAG:
            pos += 2 if data[pos] & 0x80 else 1  # length in UTF-16 units
            size = data[pos]
            if size & 0x80:
                size = (size & 0x7F) << 8 | data[pos + 1]
                pos += 1
            pos += 1
            encoding = "utf-8"
        else:
            (size,) = struct.unpack_from("<H", data, pos)
            if size & 0x8000:
                size = (size & 0x7FFF) << 16 | struct.unpack_from("<H", data, pos + 2)[0]
                pos += 2
            pos += 2
            size *= 2
            encoding = "utf-16-le"
        if pos + size > chunk_end:
            raise ValueError("string past the end of its pool")
        pieces.append((pos, decode(data[pos : pos + size], encoding), encoding))
    return pieces


def chunk_pieces(data, pos, end):
    pieces = []
    while pos < end:
        kind, header_size, size = struct.unpack_from("<HHI", data, pos)
        if header_size < 8 or size < header_size or pos + size > end:
            raise ValueError(f"bad resource chunk at {pos:#x}")
        if kind == RES_STRING_POOL:
            pieces += string_pool(data, pos, header_size, pos + size)
        elif kind in (RES_TABLE, RES_XML):
            pieces += chunk_pieces(data, pos + header_size, pos + size)
        elif kind == RES_TABLE_PACKAGE:
            pieces.append(utf16z(data, pos + 12, 256, pos + header_size))
            pieces += chunk_pieces(data, pos + header_size, pos + size)
        elif kind == RES_TABLE_LIBRARY:
            (count,) = struct.unpack_from("<I", data, pos + 8)
            entries = pos + header_size
            pieces += [utf16z(data, entries + 260 * i + 4, 256, pos + size) for i in range(count)]
        elif kind == RES_TABLE_OVERLAYABLE:
            pieces += [utf16z(data, pos + 8, 512, pos + size), utf16z(data, pos + 520, 512, pos + size)]
        pos += size
    return pieces


def res_pieces(data):
    (size,) = struct.unpack_from("<I", data, 4)
    if size > len(data):
        raise ValueError("resource file shorter than its header says")
    inner = []
    return chunk_pieces(data, 0, size) + binary_pieces(data, inner, size), inner  # anything after it too


# --- ELF ---------------------------------------------------------------------------------------

SHT_STRTAB = 3
SHF_EXECINSTR = 0x4


def elf_pieces(data):
    order = {1: "<", 2: ">"}[data[5]]
    if data[4] == 2:
        (shoff,) = struct.unpack_from(order + "Q", data, 0x28)
        shentsize, shnum = struct.unpack_from(order + "HH", data, 0x3A)
        fmt = order + "IIQQQQ"
    else:
        (shoff,) = struct.unpack_from(order + "I", data, 0x20)
        shentsize, shnum = struct.unpack_from(order + "HH", data, 0x2E)
        fmt = order + "IIIIII"
    pieces, tables, code, inner = [], [], [], []
    for i in range(shnum):  # none at all: every byte is read as text runs
        _, kind, flags, _, offset, size = struct.unpack_from(fmt, data, shoff + i * shentsize)
        if offset + size > len(data) and kind != 8:  # SHT_NOBITS takes no bytes of the file
            raise ValueError("section past the end of the file")
        if kind == SHT_STRTAB and size:
            tables.append((offset, offset + size))
            for raw in data[offset : offset + size].split(b"\0"):
                pieces.append((offset, decode(raw, "utf-8"), "utf-8"))
                offset += len(raw) + 1
        elif flags & SHF_EXECINSTR and kind != 8 and size:
            code.append((offset, offset + size))
            pieces += binary_pieces(data, inner, offset, offset + size, code=True)
    return pieces + except_ranges(data, tables + code, inner), inner


# --- images and fonts --------------------------------------------------------------------------

PIXEL_CHUNKS = (b"IDAT", b"fdAT", b"VP8 ", b"VP8L", b"ALPH", b"ANMF")
# Outlines, metrics, hinting, layout and bitmaps: numbers, no text. CFF is read by cff_strings.
GLYPH_TABLES = {
    *(b"glyf", b"loca", b"gvar", b"cvar", b"hmtx", b"vmtx", b"hdmx", b"cvt ", b"fpgm", b"prep"),
    *(b"GPOS", b"GSUB", b"GDEF", b"kern", b"cmap", b"HVAR", b"VVAR", b"MVAR", b"LTSH", b"VDMX"),
    *(b"CBDT", b"CBLC", b"EBDT", b"EBLC", b"sbix", b"COLR", b"CPAL", b"CFF ", b"CFF2"),
}


def chunks(data, pos, end, header):
    """(type, start, end) of the chunks of a PNG ("length type data crc") or RIFF ("type length data")."""
    while pos < end:
        if header == "png":
            (size,) = struct.unpack_from(">I", data, pos)
            kind, start, stop = data[pos + 4 : pos + 8], pos + 8, pos + 12 + size
        else:
            kind, (size,) = data[pos : pos + 4], struct.unpack_from("<I", data, pos + 4)
            start, stop = pos + 8, pos + 8 + size + (size & 1)
        if stop > end:
            raise ValueError(f"chunk past the end of the file at {pos:#x}")
        yield kind, start, stop
        if kind == b"IEND":
            return
        pos = stop


def image_pieces(data):
    """Every chunk but the pixel data; compressed text and ICC profiles of a PNG decompressed, as
    files inside it."""
    if data[:4] == b"RIFF":
        (size,) = struct.unpack_from("<I", data, 4)
        found = list(chunks(data, 12, min(len(data), 8 + size), "riff"))
    else:
        found = list(chunks(data, 8, len(data), "png"))
    inner = []
    for kind, start, stop in found:
        body = data[start : stop - 4]  # the CRC after it
        if kind in (b"zTXt", b"iCCP"):  # name, NUL, method, compressed data
            inner.append((f"{kind.decode()}@{start:#x}", zlib.decompress(body[body.index(b"\0") + 2 :])))
        elif kind == b"iTXt" and body[body.index(b"\0") + 1]:  # name, NUL, compressed?, method
            rest = body[body.index(b"\0") + 3 :]
            rest = rest[rest.index(b"\0") + 1 :]  # after the language
            inner.append((f"iTXt@{start:#x}", zlib.decompress(rest[rest.index(b"\0") + 1 :])))
    pixels = [(start, stop) for kind, start, stop in found if kind in PIXEL_CHUNKS]
    return except_ranges(data, pixels, inner), inner


def jpeg_pieces(data):
    """Every segment but the compressed scan data: EXIF, XMP, ICC profile, comments."""
    pos, scans = 2, []
    while pos < len(data):
        if data[pos] != 0xFF:
            raise ValueError(f"no JPEG marker at {pos:#x}")
        marker = data[pos + 1]
        if marker == 0xD9:  # end of image
            break
        if marker == 0xFF or 0xD0 <= marker <= 0xD7 or marker == 0x01:  # fill, restart: no length
            pos += 1 if marker == 0xFF else 2
            continue
        (size,) = struct.unpack_from(">H", data, pos + 2)
        pos += 2 + size
        if marker == 0xDA:  # start of scan: compressed data up to the next marker but 00 and RSTn
            start = pos
            while data[pos] != 0xFF or data[pos + 1] == 0 or 0xD0 <= data[pos + 1] <= 0xD7:
                pos = data.index(b"\xff", pos + 1)
            scans.append((start, pos))
    inner = []
    return except_ranges(data, scans, inner), inner


def gif_pieces(data):
    """Every block but the compressed image data: comments, application blocks (XMP)."""

    def colours(flags):
        return 3 << ((flags & 7) + 1) if flags & 0x80 else 0

    def after_sub_blocks(pos):
        while data[pos]:
            pos += 1 + data[pos]
        return pos + 1

    pos, images = 13 + colours(data[10]), []
    while data[pos] != 0x3B:  # trailer
        if data[pos] == 0x21:  # extension: label, sub-blocks
            pos = after_sub_blocks(pos + 2)
        elif data[pos] == 0x2C:  # image: descriptor, colour table, LZW code size, sub-blocks
            pos += 11 + colours(data[pos + 9])
            images.append((pos, after_sub_blocks(pos)))
            pos = images[-1][1]
        else:
            raise ValueError(f"no GIF block at {pos:#x}")
    inner = []
    return except_ranges(data, images, inner), inner


def cff_strings(data, start, end):
    """The font names (Name INDEX) and strings (String INDEX) of a CFF table; its outlines are
    elsewhere in it."""
    pieces = []
    pos = start + data[start + 2]  # header size
    for index in range(3):  # Name, Top DICT, String
        (count,) = struct.unpack_from(">H", data, pos)
        if not count:
            pos += 2
            continue
        size = data[pos + 2]
        offsets = [int.from_bytes(data[pos + 3 + i * size : pos + 3 + (i + 1) * size], "big") for i in range(count + 1)]
        base = pos + 2 + (count + 1) * size  # offsets count from 1
        if base + offsets[-1] > end:
            raise ValueError("CFF INDEX past the end of its table")
        if index != 1:
            pieces += [(base + a, decode(data[base + a : base + b], "latin-1"), "latin-1") for a, b in zip(offsets, offsets[1:])]
        pos = base + offsets[-1]
    return pieces


def font_pieces(data):
    """The strings of the name tables and CFF tables, and the text runs of every other table but
    GLYPH_TABLES."""
    if data[:4] == b"ttcf":  # a collection: several fonts, each with its table directory
        (count,) = struct.unpack_from(">I", data, 8)
        fonts = struct.unpack_from(f">{count}I", data, 12)
    else:
        fonts = (0,)
    names, cff, skipped = set(), set(), set()
    for font in fonts:
        (count,) = struct.unpack_from(">H", data, font + 4)
        for i in range(count):
            tag, _, table, length = struct.unpack_from(">4sIII", data, font + 12 + 16 * i)
            if table + length > len(data):
                raise ValueError(f"font table {tag!r} past the end of the file")
            if tag == b"name":
                names.add((table, length))
            if tag == b"CFF ":
                cff.add((table, length))
            if tag == b"name" or tag in GLYPH_TABLES:
                skipped.add((table, table + length))
    pieces, inner = [], []
    for table, length in sorted(names):
        records, strings = struct.unpack_from(">HH", data, table + 2)
        for r in range(records):
            platform, encoding, _, _, size, start = struct.unpack_from(">6H", data, table + 6 + 12 * r)
            pos = table + strings + start
            if pos + size > table + length:
                raise ValueError("name past the end of the name table")
            # Unicode, Windows and ISO 10646 names are UTF-16, Mac and ASCII ones 8-bit
            unicode = platform in (0, 3) or (platform, encoding) == (2, 1)
            encoding = "utf-16-be" if unicode else "latin-1"
            pieces.append((pos, decode(data[pos : pos + size], encoding), encoding))
    for table, length in sorted(cff):
        pieces += cff_strings(data, table, table + length)
    return pieces + except_ranges(data, skipped, inner), inner


# --- archives and files ------------------------------------------------------------------------

MAX_DEPTH = 8


def signing_block(data, directory):
    """(start, end) of the APK Signing Block, which sits right before the central directory."""
    if not 32 <= directory <= len(data) or data[directory - 16 : directory] != b"APK Sig Block 42":
        return None
    (size,) = struct.unpack_from("<Q", data, directory - 24)
    start = directory - size - 8
    return (start, directory) if start >= 0 else None


def zip_records(data, z):
    """(byte ranges every record takes, names and comments as pieces) of an archive: local headers
    with their data, the central directory record by record, its end."""
    taken, pieces = [], []
    for info in z.infolist():
        h = info.header_offset
        name_size, extra_size = struct.unpack_from("<HH", data, h + 26)
        stop = h + 30 + name_size + extra_size + info.compress_size
        if info.flag_bits & 8:  # data descriptor after the data, its signature optional
            stop += 16 if data[stop : stop + 4] == b"PK\x07\x08" else 12
        taken.append((h, stop))
        pieces.append((h + 30, decode(data[h + 30 : h + 30 + name_size], "utf-8"), "utf-8"))
    pos = z.start_dir
    for _ in z.infolist():
        if data[pos : pos + 4] != b"PK\x01\x02":
            raise ValueError(f"no central directory record at {pos:#x}")
        name_size, extra_size, comment_size = struct.unpack_from("<HHH", data, pos + 28)
        pieces.append((pos + 46, decode(data[pos + 46 : pos + 46 + name_size], "utf-8"), "utf-8"))
        comment = pos + 46 + name_size + extra_size
        pieces.append((comment, decode(data[comment : comment + comment_size], "utf-8"), "utf-8"))
        pos = comment + comment_size
    eocd = data.find(b"PK\x05\x06", pos)  # after zip64 records, if any
    if eocd < 0:
        raise ValueError("no end of central directory")
    (comment_size,) = struct.unpack_from("<H", data, eocd + 20)
    pieces.append((eocd + 22, decode(data[eocd + 22 : eocd + 22 + comment_size], "utf-8"), "utf-8"))
    taken.append((z.start_dir, eocd + 22 + comment_size))
    return taken, pieces


def scan_zip(name, data, z, hits, depth):
    inner = []
    taken, pieces = zip_records(data, z)
    block = signing_block(data, z.start_dir)
    if block:
        taken.append(block)
        find(f"{name}!(APK Signing Block)", binary_pieces(data, inner, *block), hits)
    find(f"{name} (names and comments)", pieces, hits)
    find(f"{name} (bytes no record takes)", except_ranges(data, taken, inner), hits)
    for info in z.infolist():
        if info.is_dir():
            continue
        try:
            entry = z.read(info)
        except Exception as e:  # noqa: BLE001 (bad CRC, broken or unknown compression, encryption)
            hits.append((f"{name}!{info.filename}", None, f"cannot be read from the archive ({e})"))
            continue
        scan(f"{name}!{info.filename}", entry, hits, depth + 1)
    for part, content in inner:
        scan(f"{name}!{part}", content, hits, depth + 1)


def reader(data):
    """(format, function reading its pieces and inner files) for a file that starts like a
    format, else None."""
    if data[:4] == b"dex\n":
        return "DEX", dex_pieces
    if data[:4] == b"\x7fELF":
        return "ELF", elf_pieces
    if data[:4] in (b"\3\0\x08\0", b"\2\0\x0c\0"):
        return "a resource file", res_pieces
    if data[:8] == b"\x89PNG\r\n\x1a\n" or (data[:4] == b"RIFF" and data[8:12] == b"WEBP"):
        return "an image", image_pieces
    if data[:3] == b"\xff\xd8\xff":
        return "a JPEG image", jpeg_pieces
    if data[:6] in (b"GIF87a", b"GIF89a"):
        return "a GIF image", gif_pieces
    if data[:4] in (b"\0\1\0\0", b"OTTO", b"ttcf"):
        return "a font", font_pieces
    return None


BROKEN = (struct.error, IndexError, KeyError, ValueError, OverflowError, RecursionError, zlib.error)


def scan(name, data, hits, depth=0):
    """Adds (name, offset, text) to hits for every upstream name in data, and (name, None,
    reason) when data cannot be read as the format it starts like."""
    if depth > MAX_DEPTH:  # files inside files inside files...
        hits.append((name, None, f"nested {depth} files deep: not read"))
        return
    found = reader(data)
    if not found and (data[:4] == b"PK\x03\x04" or b"PK\x05\x06" in data[-0x10000 - 22 :]):
        try:
            z = zipfile.ZipFile(io.BytesIO(data))
        except Exception as e:  # noqa: BLE001 (no central directory, bad names, ...)
            if data[:4] == b"PK\x03\x04":
                hits.append((name, None, f"cannot be read as a ZIP archive ({e}); read as any other file"))
        else:
            with z:
                scan_zip(name, data, z, hits, depth)
            return
    pieces = None
    if found:
        try:
            pieces, inner = found[1](data)
        except BROKEN as e:
            hits.append((name, None, f"cannot be read as {found[0]} ({e}); read as any other file"))
    if pieces is None:
        pieces, inner = any_pieces(data)
    find(name, pieces, hits)
    for part, content in inner:
        scan(f"{name}!{part}", content, hits, depth + 1)


def main(paths):
    hits = []
    for path in paths:
        with open(path, "rb") as f:
            scan(path, f.read(), hits)
    for name, offset, text in hits:
        print(f"{name}: {text}" if offset is None else f"{name} @{offset:#x}: {text}")
    unreadable = sum(offset is None for _, offset, _ in hits)
    print(f"{len(hits) - unreadable} upstream name(s) found, {unreadable} file(s) unreadable", file=sys.stderr)
    return 1 if hits else 0


def encode(text):
    """The Manager's Hidden.s() form of text: every character shifted up by one."""
    return "".join(chr(ord(c) + 1) for c in text)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    sys.stdout.reconfigure(errors="backslashreplace")  # entry names on a non-UTF-8 console
    if sys.argv[1] == "--encode":
        for text in sys.argv[2:]:
            print(f"{text!r} -> {encode(text)!r}")
        sys.exit(0)
    sys.exit(main(sys.argv[1:]))
