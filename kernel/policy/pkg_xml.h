#ifndef __KSU_H_PKG_XML
#define __KSU_H_PKG_XML

#include <linux/types.h>

// Signing certificate of one package as PackageManager recorded it in /data/system/packages.xml,
// after it verified the APK itself. Reads binary XML (ABX, Android 12+) and text XML.
// Returns the DER length copied to cert, or:
//   -ENOENT    pkg is not listed
//   -ENODATA   pkg has no signer
//   -ENOTUNIQ  pkg has more than one signer
//   -E2BIG     the certificate is larger than cap
//   -EBADMSG   malformed or truncated file
//   -ENOMEM
ssize_t ksu_pkg_xml_find_cert(const u8 *xml, size_t len, const char *pkg, u8 *cert, size_t cap);

#endif
