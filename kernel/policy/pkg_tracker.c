#include "ksu.h"
#include <linux/cred.h>
#include <linux/err.h>
#include <linux/fs.h>
#include <linux/ctype.h>
#include <linux/kernel.h>
#include <linux/list.h>
#include <linux/mm.h>
#include <linux/moduleparam.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/types.h>
#include <crypto/hash.h>

#include "policy/allowlist.h"
#include "policy/pkg_tracker.h"
#include "policy/pkg_xml.h"
#include "klog.h" // IWYU pragma: keep

#define SYSTEM_PACKAGES_LIST_PATH "/data/system/packages.list"
#define SYSTEM_PACKAGES_XML_PATH "/data/system/packages.xml"
#define PACKAGES_XML_MAX_SIZE (32 << 20)

// Patch-time root seed, written by `camd boot-patch --seed` into ksu_config:
//   seed=<nonce>,<pkg>:<appid>[,<pkg>:<appid>...]
// Each nonce is applied once; the applied nonce is kept in KSU_SEED_MARKER.
#define KSU_SEED_MARKER "/data/adb/cam/.seed"
#define KSU_SEED_NONCE_LEN 16
#define KSU_SEED_MAX_ENTRIES 32
#define KSU_SEED_MIN_APPID 10000
#define KSU_SEED_MAX_APPID 19999

static char *ksu_seed;
module_param_named(seed, ksu_seed, charp, 0);

// Applied this boot, so a failed marker write does not re-grant on every packages.list change.
static bool ksu_seed_done;

struct uid_data {
    struct list_head list;
    u32 uid;
    char package[KSU_MAX_PACKAGE_NAME];
};

static bool is_uid_exist(uid_t uid, char *package, void *data)
{
    struct list_head *list = (struct list_head *)data;
    struct uid_data *np;

    bool exist = false;
    list_for_each_entry (np, list, list) {
        if (np->uid == uid % PER_USER_RANGE && strncmp(np->package, package, KSU_MAX_PACKAGE_NAME) == 0) {
            exist = true;
            break;
        }
    }
    return exist;
}

// Returns false if packages.list could not be read; uid_list is filled with what was parsed.
// *complete is false when a line was skipped: the list then cannot say which apps are gone.
static bool read_packages_list(struct list_head *uid_list, bool *complete)
{
    *complete = true;

    struct file *fp = filp_open(SYSTEM_PACKAGES_LIST_PATH, O_RDONLY, 0);
    if (IS_ERR(fp)) {
        pr_err("%s: open " SYSTEM_PACKAGES_LIST_PATH " failed: %ld\n", __func__, PTR_ERR(fp));
        return false;
    }

    char chr = 0;
    loff_t pos = 0;
    loff_t line_start = 0;
    char buf[KSU_MAX_PACKAGE_NAME];
    for (;;) {
        ssize_t count = kernel_read(fp, &chr, sizeof(chr), &pos);
        if (count != sizeof(chr))
            break;
        if (chr != '\n')
            continue;

        count = kernel_read(fp, buf, sizeof(buf) - 1, &line_start);
        if (count <= 0) {
            *complete = false;
            break;
        }
        buf[count] = '\0';
        // the read runs into the next line: keep this one only
        char *nl = strchr(buf, '\n');
        if (nl)
            *nl = '\0';
        // the next line starts after this '\n', whatever this one holds
        line_start = pos;

        char *tmp = buf;
        const char *delim = " ";
        char *package = strsep(&tmp, delim);
        char *uid = strsep(&tmp, delim);
        if (!uid || !package) {
            pr_err("update_uid: package or uid is NULL!\n");
            *complete = false;
            continue;
        }

        u32 res;
        if (kstrtou32(uid, 10, &res)) {
            pr_err("update_uid: uid parse err\n");
            *complete = false;
            continue;
        }

        struct uid_data *data = kzalloc(sizeof(struct uid_data), GFP_KERNEL);
        if (!data) {
            filp_close(fp, 0);
            return false;
        }
        data->uid = res;
        strscpy(data->package, package, sizeof(data->package));
        list_add_tail(&data->list, uid_list);
    }
    filp_close(fp, 0);
    return true;
}

static bool seed_nonce_valid(const char *nonce)
{
    int i;

    if (strlen(nonce) != KSU_SEED_NONCE_LEN)
        return false;
    for (i = 0; i < KSU_SEED_NONCE_LEN; i++) {
        if (!isdigit(nonce[i]) && !(nonce[i] >= 'a' && nonce[i] <= 'f'))
            return false;
    }
    return true;
}

static bool seed_package_valid(const char *pkg)
{
    size_t len = strlen(pkg);
    size_t i;

    if (len == 0 || len >= KSU_MAX_PACKAGE_NAME)
        return false;
    for (i = 0; i < len; i++) {
        if (!isalnum(pkg[i]) && pkg[i] != '.' && pkg[i] != '_')
            return false;
    }
    return true;
}

// Splits "<pkg>:<appid>" in place.
static bool seed_parse_entry(char *entry, char **pkg, u32 *appid)
{
    char *sep = strchr(entry, ':');

    if (!sep)
        return false;
    *sep = '\0';
    *pkg = entry;
    if (!seed_package_valid(*pkg))
        return false;
    if (kstrtou32(sep + 1, 10, appid))
        return false;
    return *appid >= KSU_SEED_MIN_APPID && *appid <= KSU_SEED_MAX_APPID;
}

static bool seed_nonce_applied(const char *nonce)
{
    char buf[KSU_SEED_NONCE_LEN];
    loff_t off = 0;
    bool applied = false;
    struct file *fp = filp_open(KSU_SEED_MARKER, O_RDONLY, 0);

    if (IS_ERR(fp))
        return false;
    if (kernel_read(fp, buf, sizeof(buf), &off) == sizeof(buf))
        applied = memcmp(buf, nonce, sizeof(buf)) == 0;
    filp_close(fp, 0);
    return applied;
}

static void seed_write_marker(const char *nonce)
{
    loff_t off = 0;
    struct file *fp = filp_open(KSU_SEED_MARKER, O_WRONLY | O_CREAT | O_TRUNC, 0600);

    if (IS_ERR(fp)) {
        pr_warn("seed: write " KSU_SEED_MARKER " failed: %ld, will retry next boot\n", PTR_ERR(fp));
        return;
    }
    if (kernel_write(fp, nonce, KSU_SEED_NONCE_LEN, &off) != KSU_SEED_NONCE_LEN)
        pr_warn("seed: short write to " KSU_SEED_MARKER "\n");
    filp_close(fp, 0);
}

static bool seed_package_installed(struct list_head *pkgs, const char *pkg, u32 appid)
{
    struct uid_data *np;

    list_for_each_entry (np, pkgs, list) {
        if (np->uid == appid && strncmp(np->package, pkg, KSU_MAX_PACKAGE_NAME) == 0)
            return true;
    }
    return false;
}

static bool is_manager_package(const char *pkg);

// Caller holds ksu_cred. Runs from post-fs-data on: the first boot after flashing has no
// /data/adb/camd yet, so boot-completed is never reported. packages.list from the previous
// boot is fine here because every entry must match both package and appid.
static void ksu_seed_apply(struct list_head *pkgs)
{
    char *copy, *cur, *nonce, *entry, *pkg;
    const char *p;
    int entries = 0, granted = 0;
    u32 appid;

    if (!ksu_seed || !*ksu_seed || ksu_seed_done)
        return;
    ksu_seed_done = true;

    copy = kstrdup(ksu_seed, GFP_KERNEL);
    if (!copy)
        return;

    cur = copy;
    nonce = strsep(&cur, ",");
    if (!seed_nonce_valid(nonce)) {
        pr_warn("seed: invalid nonce, ignoring seed\n");
        goto out;
    }

    for (p = cur; p && *p; p++) {
        if (*p == ',')
            entries++;
    }
    if (cur && *cur)
        entries++;
    if (entries == 0 || entries > KSU_SEED_MAX_ENTRIES) {
        pr_warn("seed: invalid entry count %d, ignoring seed\n", entries);
        goto out;
    }

    if (seed_nonce_applied(nonce)) {
        pr_info("seed: nonce %s already applied\n", nonce);
        goto out;
    }

    while ((entry = strsep(&cur, ",")) != NULL) {
        if (!seed_parse_entry(entry, &pkg, &appid)) {
            pr_warn("seed: invalid entry skipped\n");
            continue;
        }
        if (is_manager_package(pkg)) {
            pr_info("seed: skip %s: the manager pin checks its signature\n", pkg);
            continue;
        }
        if (!seed_package_installed(pkgs, pkg, appid)) {
            pr_info("seed: skip %s(%u): not installed or uid mismatch\n", pkg, appid);
            continue;
        }
        if (ksu_grant_default_root(pkg, appid)) {
            pr_warn("seed: grant %s(%u) failed\n", pkg, appid);
            continue;
        }
        pr_info("seed: granted %s(%u)\n", pkg, appid);
        granted++;
    }

    if (granted)
        ksu_persistent_allow_list();
    seed_write_marker(nonce);
out:
    kfree(copy);
}

// The Manager app always keeps root. After an uninstall/reinstall it comes back under a new appid
// (and the old one is pruned), so it is found by package name on every packages.list change, then
// trusted only if signed by a Manager key. Define KSU_MANAGER_PACKAGE at build time for another name.
#ifndef KSU_MANAGER_PACKAGE
#define KSU_MANAGER_PACKAGE "cam.su.kernel"
#endif

// SHA-256 of the certificate that signs release Managers (CN=SU Kernel, O=Cam), the
// "certificate SHA-256 digest" of `apksigner verify --print-certs`. KSU_MANAGER_CERT_EXTRA trusts
// one more at build time, e.g. a debug key; both are lower-case hex.
#ifndef KSU_MANAGER_CERT
#define KSU_MANAGER_CERT "1bee10d239c9f1b69c6af8e8e818f6ecdea711e251314ce12e78a38f3d00a318"
#endif

// Offline backup key (CN=SU Kernel Backup, O=Cam), never used to sign anything yet. If the release
// key is lost, a Manager signed with this one still gets root from every LKM already flashed
// (users reinstall the Manager once: Android refuses the signer change as an update).
#ifndef KSU_MANAGER_CERT_BACKUP
#define KSU_MANAGER_CERT_BACKUP "38d0a6b8261c83f99a0d664da871d05ace67e64f1a10c19499b1a368102de4d9"
#endif

static const char *const manager_certs[] = {
    KSU_MANAGER_CERT,
    KSU_MANAGER_CERT_BACKUP,
#ifdef KSU_MANAGER_CERT_EXTRA
    KSU_MANAGER_CERT_EXTRA,
#endif
};

// An X.509 certificate in DER; the release one is 1261 bytes.
#define MANAGER_CERT_MAX_SIZE 8192
#define SHA256_LEN 32

static bool is_manager_package(const char *pkg)
{
    return strncmp(pkg, KSU_MANAGER_PACKAGE, KSU_MAX_PACKAGE_NAME) == 0;
}

// The whole of packages.xml in a kvmalloc'ed buffer, or NULL.
static u8 *read_packages_xml(size_t *len)
{
    struct file *fp;
    loff_t size, pos = 0;
    ssize_t n;
    u8 *buf = NULL;

    fp = filp_open(SYSTEM_PACKAGES_XML_PATH, O_RDONLY, 0);
    if (IS_ERR(fp)) {
        pr_err("manager pin: open " SYSTEM_PACKAGES_XML_PATH " failed: %ld\n", PTR_ERR(fp));
        return NULL;
    }
    size = i_size_read(file_inode(fp));
    if (size <= 0 || size > PACKAGES_XML_MAX_SIZE) {
        pr_err("manager pin: " SYSTEM_PACKAGES_XML_PATH " size %lld\n", (long long)size);
        goto out;
    }
    buf = kvmalloc(size, GFP_KERNEL);
    if (!buf)
        goto out;
    while (pos < size) {
        n = kernel_read(fp, buf + pos, size - pos, &pos);
        if (n <= 0)
            break;
    }
    if (pos != size) {
        pr_err("manager pin: short read of " SYSTEM_PACKAGES_XML_PATH "\n");
        kvfree(buf);
        buf = NULL;
        goto out;
    }
    *len = size;
out:
    filp_close(fp, 0);
    return buf;
}

static int cert_sha256(const u8 *data, size_t len, u8 *digest)
{
    struct crypto_shash *tfm;
    struct shash_desc *desc;
    int ret;

    tfm = crypto_alloc_shash("sha256", 0, 0);
    if (IS_ERR(tfm))
        return PTR_ERR(tfm);
    desc = kzalloc(sizeof(*desc) + crypto_shash_descsize(tfm), GFP_KERNEL);
    if (!desc) {
        crypto_free_shash(tfm);
        return -ENOMEM;
    }
    desc->tfm = tfm;
    ret = crypto_shash_digest(desc, data, len, digest);
    kfree(desc);
    crypto_free_shash(tfm);
    return ret;
}

// Whether PackageManager, which verified the APK when installing it, recorded a Manager key as
// the one signer of KSU_MANAGER_PACKAGE, under the appid packages.list gives it. The APK itself
// is never read here.
// 0: trusted. -EKEYREJECTED: signed by something else. Other errors: could not tell (-ESTALE:
// packages.xml and packages.list disagree on the appid, e.g. one written before the other).
static int manager_cert_check(u32 appid)
{
    static const char hex_digits[] = "0123456789abcdef";
    u8 digest[SHA256_LEN];
    char hex[SHA256_LEN * 2 + 1];
    size_t xml_len = 0, i;
    u8 *xml, *cert;
    u32 xml_appid = 0;
    ssize_t n;
    int ret;

    xml = read_packages_xml(&xml_len);
    if (!xml)
        return -EIO;
    cert = kmalloc(MANAGER_CERT_MAX_SIZE, GFP_KERNEL);
    if (!cert) {
        ret = -ENOMEM;
        goto out;
    }

    n = ksu_pkg_xml_find_cert(xml, xml_len, KSU_MANAGER_PACKAGE, cert, MANAGER_CERT_MAX_SIZE, &xml_appid);
    if (n == -ENOTUNIQ || n == -E2BIG) {
        // a Manager APK has exactly one signer, with a certificate far below the limit
        pr_warn("manager pin: %s signer rejected: %zd\n", KSU_MANAGER_PACKAGE, n);
        ret = -EKEYREJECTED;
        goto out;
    }
    if (n < 0) {
        pr_err("manager pin: no signer for %s in " SYSTEM_PACKAGES_XML_PATH ": %zd\n", KSU_MANAGER_PACKAGE, n);
        ret = n;
        goto out;
    }

    ret = cert_sha256(cert, n, digest);
    if (ret) {
        pr_err("manager pin: sha256 failed: %d\n", ret);
        goto out;
    }
    for (i = 0; i < SHA256_LEN; i++) {
        hex[2 * i] = hex_digits[digest[i] >> 4];
        hex[2 * i + 1] = hex_digits[digest[i] & 0xf];
    }
    hex[SHA256_LEN * 2] = '\0';

    ret = -EKEYREJECTED;
    for (i = 0; i < ARRAY_SIZE(manager_certs); i++) {
        if (!strcmp(hex, manager_certs[i])) {
            ret = 0;
            break;
        }
    }
    if (ret) {
        // an untrusted signer is refused whatever the appid: revoking is the safe side
        pr_warn("manager pin: %s is signed by untrusted certificate %s\n", KSU_MANAGER_PACKAGE, hex);
    } else if (xml_appid != appid) {
        // a trusted signer recorded for another appid vouches for nothing here
        pr_warn("manager pin: %s is appid %u in " SYSTEM_PACKAGES_XML_PATH ", %u in " SYSTEM_PACKAGES_LIST_PATH "\n",
                KSU_MANAGER_PACKAGE, xml_appid, appid);
        ret = -ESTALE;
    }
out:
    kfree(cert);
    kvfree(xml);
    return ret;
}

// Caller holds ksu_cred.
static void ksu_manager_pin_apply(struct list_head *pkgs)
{
    struct uid_data *np;
    int ret;

    list_for_each_entry (np, pkgs, list) {
        if (!is_manager_package(np->package))
            continue;
        // Regular app appids only, same bounds as the seed.
        if (np->uid < KSU_SEED_MIN_APPID || np->uid > KSU_SEED_MAX_APPID)
            return;
        // Checked even when already allowed: a reused appid would otherwise hand an impostor
        // the root of the Manager it replaced.
        ret = manager_cert_check(np->uid);
        if (ret == -EKEYREJECTED) {
            if (!__ksu_is_allow_uid(np->uid))
                return;
            if (ksu_revoke_root(np->package, np->uid)) {
                pr_warn("manager pin: revoke %s(%u) failed\n", np->package, np->uid);
                return;
            }
            pr_warn("manager pin: revoked %s(%u)\n", np->package, np->uid);
            ksu_persistent_allow_list();
            return;
        }
        if (ret) {
            pr_warn("manager pin: cannot check %s(%u): %d, not granting\n", np->package, np->uid, ret);
            return;
        }
        if (__ksu_is_allow_uid(np->uid))
            return;
        if (ksu_grant_default_root(np->package, np->uid)) {
            pr_warn("manager pin: grant %s(%u) failed\n", np->package, np->uid);
            return;
        }
        pr_info("manager pin: granted %s(%u)\n", np->package, np->uid);
        ksu_persistent_allow_list();
        return;
    }
}

void ksu_pkg_tracker_update(void)
{
    struct list_head uid_list;
    struct uid_data *np, *n;
    bool complete;
    const struct cred *old_cred = override_creds(ksu_cred);

    INIT_LIST_HEAD(&uid_list);
    if (read_packages_list(&uid_list, &complete)) {
        ksu_seed_apply(&uid_list);
        ksu_manager_pin_apply(&uid_list);
        // a skipped line could be any app: pruning now would drop its root and profile
        if (complete)
            ksu_prune_allowlist(is_uid_exist, &uid_list);
        else
            pr_warn("packages.list has unreadable lines, skip prune\n");
    }

    list_for_each_entry_safe (np, n, &uid_list, list) {
        list_del(&np->list);
        kfree(np);
    }
    revert_creds(old_cred);
}
