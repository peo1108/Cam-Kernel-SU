// Writes packages.xml fixtures the way PackageManager does: binary XML as BinaryXmlSerializer
// lays it out (big-endian, interned names), or text XML as FastXmlSerializer does (keys in
// upper-case hex from attributeBytesHex).
#ifndef KSU_TEST_XML_BUILDER_H
#define KSU_TEST_XML_BUILDER_H

#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

struct xb {
    uint8_t buf[1 << 17];
    size_t len;
    bool abx;
    bool tag_open; // text: "<name ..." written, its ">" not yet
    char pool[512][64];
    int pool_n;
};

static void xb_put(struct xb *x, const void *p, size_t n)
{
    assert(x->len + n <= sizeof(x->buf));
    memcpy(x->buf + x->len, p, n);
    x->len += n;
}

static void xb_byte(struct xb *x, uint8_t b)
{
    xb_put(x, &b, 1);
}

static void xb_u16(struct xb *x, uint32_t v)
{
    xb_byte(x, (uint8_t)(v >> 8));
    xb_byte(x, (uint8_t)v);
}

static void xb_u32(struct xb *x, uint32_t v)
{
    xb_u16(x, v >> 16);
    xb_u16(x, v & 0xffff);
}

static void xb_text(struct xb *x, const char *s)
{
    xb_put(x, s, strlen(s));
}

static void xb_utf(struct xb *x, const char *s)
{
    xb_u16(x, (uint32_t)strlen(s));
    xb_text(x, s);
}

static void xb_interned(struct xb *x, const char *s)
{
    for (int i = 0; i < x->pool_n; i++) {
        if (!strcmp(x->pool[i], s)) {
            xb_u16(x, (uint32_t)i);
            return;
        }
    }
    assert(x->pool_n < 512 && strlen(s) < 64);
    strcpy(x->pool[x->pool_n++], s);
    xb_u16(x, 0xffff);
    xb_utf(x, s);
}

static void xb_close_open_tag(struct xb *x)
{
    if (x->tag_open) {
        xb_text(x, ">");
        x->tag_open = false;
    }
}

static void xb_begin(struct xb *x, bool abx)
{
    memset(x, 0, sizeof(*x));
    x->abx = abx;
    if (abx) {
        xb_put(x, "ABX\0", 4);
        xb_byte(x, 0x10); // START_DOCUMENT
    } else {
        xb_text(x, "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n");
    }
}

static void xb_start(struct xb *x, const char *name)
{
    if (x->abx) {
        xb_byte(x, 0x32);
        xb_interned(x, name);
        return;
    }
    xb_close_open_tag(x);
    xb_text(x, "<");
    xb_text(x, name);
    x->tag_open = true;
}

static void xb_end(struct xb *x, const char *name)
{
    if (x->abx) {
        xb_byte(x, 0x33);
        xb_interned(x, name);
        return;
    }
    if (x->tag_open) {
        xb_text(x, " />");
        x->tag_open = false;
        return;
    }
    xb_text(x, "</");
    xb_text(x, name);
    xb_text(x, ">");
}

static void xb_attr(struct xb *x, const char *name, const char *value)
{
    if (x->abx) {
        xb_byte(x, 0x2f);
        xb_interned(x, name);
        xb_utf(x, value);
        return;
    }
    xb_text(x, " ");
    xb_text(x, name);
    xb_text(x, "=\"");
    xb_text(x, value);
    xb_text(x, "\"");
}

static void xb_attr_int(struct xb *x, const char *name, uint32_t v)
{
    char s[16];
    if (x->abx) {
        xb_byte(x, 0x6f);
        xb_interned(x, name);
        xb_u32(x, v);
        return;
    }
    snprintf(s, sizeof(s), "%u", v);
    xb_attr(x, name, s);
}

static void xb_attr_hex(struct xb *x, const char *name, const uint8_t *p, size_t n)
{
    static const char digits[] = "0123456789ABCDEF";
    if (x->abx) {
        xb_byte(x, 0x4f);
        xb_interned(x, name);
        xb_u16(x, (uint32_t)n);
        xb_put(x, p, n);
        return;
    }
    xb_text(x, " ");
    xb_text(x, name);
    xb_text(x, "=\"");
    for (size_t i = 0; i < n; i++) {
        xb_byte(x, (uint8_t)digits[p[i] >> 4]);
        xb_byte(x, (uint8_t)digits[p[i] & 0xf]);
    }
    xb_text(x, "\"");
}

// Value types packages.xml also uses, so the reader has to step over them.
static void xb_attr_interned(struct xb *x, const char *name, const char *value)
{
    if (!x->abx) {
        xb_attr(x, name, value);
        return;
    }
    xb_byte(x, 0x3f);
    xb_interned(x, name);
    xb_interned(x, value);
}

static void xb_attr_long_hex(struct xb *x, const char *name, uint64_t v)
{
    char s[24];
    if (!x->abx) {
        snprintf(s, sizeof(s), "%llx", (unsigned long long)v);
        xb_attr(x, name, s);
        return;
    }
    xb_byte(x, 0x9f);
    xb_interned(x, name);
    xb_u32(x, (uint32_t)(v >> 32));
    xb_u32(x, (uint32_t)v);
}

static void xb_attr_bool(struct xb *x, const char *name, bool v)
{
    if (!x->abx) {
        xb_attr(x, name, v ? "true" : "false");
        return;
    }
    xb_byte(x, v ? 0xcf : 0xdf);
    xb_interned(x, name);
}

static void xb_attr_float(struct xb *x, const char *name)
{
    if (!x->abx) {
        xb_attr(x, name, "1.0");
        return;
    }
    xb_byte(x, 0xaf);
    xb_interned(x, name);
    xb_u32(x, 0x3f800000);
}

static void xb_comment(struct xb *x, const char *s)
{
    if (!x->abx) {
        xb_close_open_tag(x);
        xb_text(x, "<!--");
        xb_text(x, s);
        xb_text(x, "-->");
        return;
    }
    xb_byte(x, 0x29); // COMMENT | TYPE_STRING
    xb_utf(x, s);
}

static void xb_finish(struct xb *x)
{
    if (x->abx)
        xb_byte(x, 0x11); // END_DOCUMENT
    else
        xb_text(x, "\n");
}

// <cert index=".." [key=".."] />; key NULL when the index was written earlier in the file.
static void xb_cert(struct xb *x, uint32_t index, const uint8_t *key, size_t key_len)
{
    xb_start(x, "cert");
    xb_attr_int(x, "index", index);
    if (key)
        xb_attr_hex(x, "key", key, key_len);
    xb_end(x, "cert");
}

#endif
