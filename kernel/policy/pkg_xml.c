#include <linux/errno.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/types.h>

#include "policy/pkg_xml.h"

// What PackageSignatures.writeXml leaves in packages.xml. A certificate's key is written only at
// its first use in the file (maybe under another package); later uses carry just the index:
//   <packages>
//     <package name="pkg" ...>
//       <sigs count="1" schemeVersion="2">
//         <cert index="3" key="3082..." />
//         <pastSigs ...><cert index="4" key="..." flags="..." /></pastSigs>
//       </sigs>
//     </package>
//   </packages>
// Only the <cert> directly under <sigs> is the current signer; <pastSigs> are rotated-out keys.

// Binary XML, see com.android.internal.util.BinaryXmlSerializer. Big-endian.
#define ABX_MAGIC "ABX\0"
#define ABX_MAGIC_LEN 4
#define ABX_START_DOCUMENT 0
#define ABX_END_DOCUMENT 1
#define ABX_START_TAG 2
#define ABX_END_TAG 3
#define ABX_TEXT 4
#define ABX_DOCDECL 10
#define ABX_ATTRIBUTE 15
#define ABX_TYPE_NULL (1 << 4)
#define ABX_TYPE_STRING (2 << 4)
#define ABX_TYPE_STRING_INTERNED (3 << 4)
#define ABX_TYPE_BYTES_HEX (4 << 4)
#define ABX_TYPE_BYTES_BASE64 (5 << 4)
#define ABX_TYPE_INT (6 << 4)
#define ABX_TYPE_INT_HEX (7 << 4)
#define ABX_TYPE_LONG (8 << 4)
#define ABX_TYPE_LONG_HEX (9 << 4)
#define ABX_TYPE_FLOAT (10 << 4)
#define ABX_TYPE_DOUBLE (11 << 4)
#define ABX_TYPE_BOOLEAN_TRUE (12 << 4)
#define ABX_TYPE_BOOLEAN_FALSE (13 << 4)
#define ABX_INTERN_NEW 0xffff

enum xml_event { XML_START, XML_ATTR, XML_END, XML_EOF };
enum xml_value { XML_VAL_NONE, XML_VAL_TEXT, XML_VAL_BYTES, XML_VAL_INT };

// Points into the file buffer; never NUL-terminated.
struct xml_str {
    const u8 *p;
    size_t n;
};

struct xml_ev {
    enum xml_event kind;
    struct xml_str name;
    enum xml_value vkind;
    struct xml_str val;
    u32 ival;
};

struct xml_reader {
    const u8 *buf;
    size_t len;
    size_t pos;
    bool abx;
    // ABX interned strings
    struct xml_str *pool;
    u32 pool_n;
    u32 pool_cap;
    // text: between "<name" and its ">" or "/>"
    bool in_tag;
    struct xml_str tag;
};

static void xml_reader_init(struct xml_reader *r, const u8 *buf, size_t len)
{
    memset(r, 0, sizeof(*r));
    r->buf = buf;
    r->len = len;
    if (len >= ABX_MAGIC_LEN && !memcmp(buf, ABX_MAGIC, ABX_MAGIC_LEN)) {
        r->abx = true;
        r->pos = ABX_MAGIC_LEN;
    }
}

static void xml_reader_free(struct xml_reader *r)
{
    kfree(r->pool);
    r->pool = NULL;
}

static bool abx_take(struct xml_reader *r, size_t n, const u8 **out)
{
    if (n > r->len - r->pos)
        return false;
    *out = r->buf + r->pos;
    r->pos += n;
    return true;
}

static bool abx_u16(struct xml_reader *r, u32 *v)
{
    const u8 *p;

    if (!abx_take(r, 2, &p))
        return false;
    *v = (u32)p[0] << 8 | p[1];
    return true;
}

static bool abx_u32(struct xml_reader *r, u32 *v)
{
    const u8 *p;

    if (!abx_take(r, 4, &p))
        return false;
    *v = (u32)p[0] << 24 | (u32)p[1] << 16 | (u32)p[2] << 8 | p[3];
    return true;
}

// u16 length, then that many bytes (modified UTF-8 for strings).
static bool abx_counted(struct xml_reader *r, struct xml_str *s)
{
    u32 n;

    if (!abx_u16(r, &n) || !abx_take(r, n, &s->p))
        return false;
    s->n = n;
    return true;
}

static int abx_interned(struct xml_reader *r, struct xml_str *s)
{
    struct xml_str *pool;
    u32 ref, cap;

    if (!abx_u16(r, &ref))
        return -EBADMSG;
    if (ref != ABX_INTERN_NEW) {
        if (ref >= r->pool_n)
            return -EBADMSG;
        *s = r->pool[ref];
        return 0;
    }
    if (!abx_counted(r, s))
        return -EBADMSG;
    if (r->pool_n == r->pool_cap) {
        cap = r->pool_cap ? r->pool_cap * 2 : 256;
        if (cap > ABX_INTERN_NEW)
            cap = ABX_INTERN_NEW;
        if (r->pool_n >= cap)
            return -EBADMSG;
        pool = krealloc(r->pool, cap * sizeof(*pool), GFP_KERNEL);
        if (!pool)
            return -ENOMEM;
        r->pool = pool;
        r->pool_cap = cap;
    }
    r->pool[r->pool_n++] = *s;
    return 0;
}

static int abx_value(struct xml_reader *r, u32 type, struct xml_ev *ev)
{
    const u8 *skip;

    switch (type) {
    case ABX_TYPE_STRING:
        ev->vkind = XML_VAL_TEXT;
        return abx_counted(r, &ev->val) ? 0 : -EBADMSG;
    case ABX_TYPE_STRING_INTERNED:
        ev->vkind = XML_VAL_TEXT;
        return abx_interned(r, &ev->val);
    case ABX_TYPE_BYTES_HEX:
    case ABX_TYPE_BYTES_BASE64:
        ev->vkind = XML_VAL_BYTES;
        return abx_counted(r, &ev->val) ? 0 : -EBADMSG;
    case ABX_TYPE_INT:
    case ABX_TYPE_INT_HEX:
        ev->vkind = XML_VAL_INT;
        return abx_u32(r, &ev->ival) ? 0 : -EBADMSG;
    case ABX_TYPE_FLOAT:
        return abx_take(r, 4, &skip) ? 0 : -EBADMSG;
    case ABX_TYPE_LONG:
    case ABX_TYPE_LONG_HEX:
    case ABX_TYPE_DOUBLE:
        return abx_take(r, 8, &skip) ? 0 : -EBADMSG;
    case ABX_TYPE_BOOLEAN_TRUE:
    case ABX_TYPE_BOOLEAN_FALSE:
        return 0;
    default:
        return -EBADMSG;
    }
}

static int abx_next(struct xml_reader *r, struct xml_ev *ev)
{
    struct xml_str text;
    const u8 *p;
    u32 token, type;
    int ret;

    for (;;) {
        // PackageManager always ends the document; running out first means a cut-off file.
        if (!abx_take(r, 1, &p))
            return -EBADMSG;
        token = *p & 0x0f;
        type = *p & 0xf0;
        switch (token) {
        case ABX_START_DOCUMENT:
            continue;
        case ABX_END_DOCUMENT:
            ev->kind = XML_EOF;
            return 0;
        case ABX_START_TAG:
        case ABX_END_TAG:
            if (type != ABX_TYPE_STRING_INTERNED)
                return -EBADMSG;
            ev->kind = token == ABX_START_TAG ? XML_START : XML_END;
            return abx_interned(r, &ev->name);
        case ABX_ATTRIBUTE:
            ev->kind = XML_ATTR;
            ev->vkind = XML_VAL_NONE;
            ret = abx_interned(r, &ev->name);
            if (ret)
                return ret;
            return abx_value(r, type, ev);
        default:
            // text, comments and the like: a string or nothing
            if (token < ABX_TEXT || token > ABX_DOCDECL)
                return -EBADMSG;
            if (type == ABX_TYPE_STRING) {
                if (!abx_counted(r, &text))
                    return -EBADMSG;
            } else if (type != ABX_TYPE_NULL) {
                return -EBADMSG;
            }
            continue;
        }
    }
}

static bool text_is_space(u8 c)
{
    return c == ' ' || c == '\t' || c == '\n' || c == '\r';
}

static bool text_is_name_end(u8 c)
{
    return text_is_space(c) || c == '=' || c == '/' || c == '>' || c == '<';
}

static void text_skip_space(struct xml_reader *r)
{
    while (r->pos < r->len && text_is_space(r->buf[r->pos]))
        r->pos++;
}

static bool text_at(struct xml_reader *r, const char *s)
{
    size_t n = strlen(s);

    return n <= r->len - r->pos && !memcmp(r->buf + r->pos, s, n);
}

// Moves past the next occurrence of s.
static bool text_skip_past(struct xml_reader *r, const char *s)
{
    for (; r->pos < r->len; r->pos++) {
        if (text_at(r, s)) {
            r->pos += strlen(s);
            return true;
        }
    }
    return false;
}

static bool text_name(struct xml_reader *r, struct xml_str *s)
{
    size_t start = r->pos;

    while (r->pos < r->len && !text_is_name_end(r->buf[r->pos]))
        r->pos++;
    s->p = r->buf + start;
    s->n = r->pos - start;
    return s->n > 0;
}

static int text_attr(struct xml_reader *r, struct xml_ev *ev)
{
    size_t start;
    u8 quote;

    if (!text_name(r, &ev->name))
        return -EBADMSG;
    text_skip_space(r);
    if (r->pos >= r->len || r->buf[r->pos] != '=')
        return -EBADMSG;
    r->pos++;
    text_skip_space(r);
    if (r->pos >= r->len || (r->buf[r->pos] != '"' && r->buf[r->pos] != '\''))
        return -EBADMSG;
    quote = r->buf[r->pos++];
    start = r->pos;
    while (r->pos < r->len && r->buf[r->pos] != quote)
        r->pos++;
    if (r->pos >= r->len)
        return -EBADMSG;
    // Entities are left as they are: names, indexes and hex keys never have any.
    ev->kind = XML_ATTR;
    ev->vkind = XML_VAL_TEXT;
    ev->val.p = r->buf + start;
    ev->val.n = r->pos - start;
    r->pos++;
    return 0;
}

static int text_next(struct xml_reader *r, struct xml_ev *ev)
{
    for (;;) {
        if (r->in_tag) {
            text_skip_space(r);
            if (r->pos >= r->len)
                return -EBADMSG;
            if (r->buf[r->pos] == '>') {
                r->pos++;
                r->in_tag = false;
                continue;
            }
            if (text_at(r, "/>")) {
                r->pos += 2;
                r->in_tag = false;
                ev->kind = XML_END;
                ev->name = r->tag;
                return 0;
            }
            return text_attr(r, ev);
        }

        if (!text_skip_past(r, "<")) {
            ev->kind = XML_EOF;
            return 0;
        }
        if (text_at(r, "!--")) {
            if (!text_skip_past(r, "-->"))
                return -EBADMSG;
            continue;
        }
        if (text_at(r, "![CDATA[")) {
            if (!text_skip_past(r, "]]>"))
                return -EBADMSG;
            continue;
        }
        if (text_at(r, "?")) {
            if (!text_skip_past(r, "?>"))
                return -EBADMSG;
            continue;
        }
        if (text_at(r, "!")) {
            if (!text_skip_past(r, ">"))
                return -EBADMSG;
            continue;
        }
        if (text_at(r, "/")) {
            r->pos++;
            if (!text_name(r, &ev->name))
                return -EBADMSG;
            text_skip_space(r);
            if (r->pos >= r->len || r->buf[r->pos] != '>')
                return -EBADMSG;
            r->pos++;
            ev->kind = XML_END;
            return 0;
        }
        if (!text_name(r, &ev->name))
            return -EBADMSG;
        r->in_tag = true;
        r->tag = ev->name;
        ev->kind = XML_START;
        return 0;
    }
}

static int xml_next(struct xml_reader *r, struct xml_ev *ev)
{
    return r->abx ? abx_next(r, ev) : text_next(r, ev);
}

static bool xml_str_is(struct xml_str s, const char *c)
{
    size_t n = strlen(c);

    return s.n == n && !memcmp(s.p, c, n);
}

static bool xml_val_int(const struct xml_ev *ev, u32 *out)
{
    u32 v = 0;
    size_t i;

    if (ev->vkind == XML_VAL_INT) {
        *out = ev->ival;
        return true;
    }
    // text: decimal, small enough not to overflow
    if (ev->vkind != XML_VAL_TEXT || ev->val.n == 0 || ev->val.n > 9)
        return false;
    for (i = 0; i < ev->val.n; i++) {
        if (ev->val.p[i] < '0' || ev->val.p[i] > '9')
            return false;
        v = v * 10 + (ev->val.p[i] - '0');
    }
    *out = v;
    return true;
}

static int hex_digit(u8 c)
{
    if (c >= '0' && c <= '9')
        return c - '0';
    if (c >= 'a' && c <= 'f')
        return c - 'a' + 10;
    if (c >= 'A' && c <= 'F')
        return c - 'A' + 10;
    return -1;
}

// ABX keeps the key as raw bytes; text XML as hex (upper case from attributeBytesHex, lower case
// from the older Signature.toCharsString).
static ssize_t xml_val_bytes(const struct xml_ev *ev, u8 *out, size_t cap)
{
    size_t i, n;
    int hi, lo;

    if (ev->vkind == XML_VAL_BYTES) {
        if (ev->val.n > cap)
            return -E2BIG;
        memcpy(out, ev->val.p, ev->val.n);
        return ev->val.n;
    }
    if (ev->vkind != XML_VAL_TEXT || ev->val.n % 2)
        return -EBADMSG;
    n = ev->val.n / 2;
    if (n > cap)
        return -E2BIG;
    for (i = 0; i < n; i++) {
        hi = hex_digit(ev->val.p[2 * i]);
        lo = hex_digit(ev->val.p[2 * i + 1]);
        if (hi < 0 || lo < 0)
            return -EBADMSG;
        out[i] = hi << 4 | lo;
    }
    return n;
}

// Pass 1: the certificate index of pkg's current signer, its key when defined right there, and
// pkg's appid.
static int find_signer(struct xml_reader *r, const char *pkg, u32 *index, u8 *cert, size_t cap, ssize_t *cert_len,
                       u32 *appid)
{
    struct xml_ev ev;
    int depth = 0, certs = 0, ret;
    bool attrs_of_package = false, attrs_of_signer = false;
    bool found = false, in_pkg = false, in_sigs = false, have_index = false;
    // of the <package> being read; its attributes may come in any order
    u32 pkg_appid = 0;
    ssize_t n;

    *cert_len = 0;
    *appid = 0;
    for (;;) {
        ret = xml_next(r, &ev);
        if (ret)
            return ret;
        if (ev.kind == XML_EOF)
            break;

        if (ev.kind == XML_START) {
            depth++;
            // <packages> is depth 1, <package> 2, <sigs> 3, <cert> 4
            attrs_of_package = depth == 2 && xml_str_is(ev.name, "package");
            if (attrs_of_package)
                pkg_appid = 0;
            attrs_of_signer = false;
            if (in_pkg && depth == 3 && xml_str_is(ev.name, "sigs")) {
                in_sigs = true;
            } else if (in_sigs && depth == 4 && xml_str_is(ev.name, "cert")) {
                certs++;
                attrs_of_signer = certs == 1;
            }
            continue;
        }

        if (ev.kind == XML_END) {
            attrs_of_package = attrs_of_signer = false;
            if (depth <= 0)
                return -EBADMSG;
            if (in_pkg && depth == 3)
                in_sigs = false;
            if (in_pkg && depth == 2) {
                in_pkg = false;
                *appid = pkg_appid;
                break;
            }
            depth--;
            continue;
        }

        if (attrs_of_package && xml_str_is(ev.name, "name") && ev.vkind == XML_VAL_TEXT && xml_str_is(ev.val, pkg)) {
            found = in_pkg = true;
        } else if (attrs_of_package && (xml_str_is(ev.name, "userId") || xml_str_is(ev.name, "sharedUserId"))) {
            // Settings.writePackageLPr writes one of the two; anything unreadable counts as none
            if (!xml_val_int(&ev, &pkg_appid))
                pkg_appid = 0;
        } else if (attrs_of_signer && xml_str_is(ev.name, "index")) {
            if (!xml_val_int(&ev, index))
                return -EBADMSG;
            have_index = true;
        } else if (attrs_of_signer && xml_str_is(ev.name, "key")) {
            n = xml_val_bytes(&ev, cert, cap);
            if (n < 0)
                return n;
            *cert_len = n;
        }
    }

    if (in_pkg)
        return -EBADMSG; // text file cut off inside the package
    if (!found)
        return -ENOENT;
    if (certs == 0)
        return -ENODATA;
    if (certs > 1)
        return -ENOTUNIQ;
    if (!have_index)
        return -EBADMSG;
    return 0;
}

// Pass 2: the key of certificate index, from wherever in the file it was first written.
static ssize_t find_key(struct xml_reader *r, u32 index, u8 *cert, size_t cap)
{
    struct xml_ev ev, key = { 0 };
    bool in_cert = false, have_index = false, have_key = false;
    u32 v;
    int ret;

    for (;;) {
        ret = xml_next(r, &ev);
        if (ret)
            return ret;
        if (ev.kind == XML_EOF)
            return -EBADMSG;
        if (ev.kind != XML_ATTR) {
            in_cert = ev.kind == XML_START && xml_str_is(ev.name, "cert");
            have_index = have_key = false;
            continue;
        }
        if (!in_cert)
            continue;
        if (xml_str_is(ev.name, "index")) {
            if (!xml_val_int(&ev, &v))
                return -EBADMSG;
            have_index = v == index;
        } else if (xml_str_is(ev.name, "key")) {
            key = ev;
            have_key = true;
        }
        if (have_index && have_key)
            return xml_val_bytes(&key, cert, cap);
    }
}

ssize_t ksu_pkg_xml_find_cert(const u8 *xml, size_t len, const char *pkg, u8 *cert, size_t cap, u32 *appid)
{
    struct xml_reader r;
    ssize_t n;
    u32 index = 0;
    int ret;

    xml_reader_init(&r, xml, len);
    ret = find_signer(&r, pkg, &index, cert, cap, &n, appid);
    xml_reader_free(&r);
    if (ret)
        return ret;
    if (n > 0)
        return n;

    xml_reader_init(&r, xml, len);
    n = find_key(&r, index, cert, cap);
    xml_reader_free(&r);
    return n == 0 ? -EBADMSG : n;
}
