#include "ksu.h"
#include <linux/cred.h>
#include <linux/err.h>
#include <linux/fs.h>
#include <linux/ctype.h>
#include <linux/list.h>
#include <linux/moduleparam.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/types.h>

#include "policy/allowlist.h"
#include "policy/pkg_tracker.h"
#include "klog.h" // IWYU pragma: keep

#define SYSTEM_PACKAGES_LIST_PATH "/data/system/packages.list"

// Patch-time root seed, written by `ksud boot-patch --seed` into ksu_config:
//   seed=<nonce>,<pkg>:<appid>[,<pkg>:<appid>...]
// Each nonce is applied once; the applied nonce is kept in KSU_SEED_MARKER.
#define KSU_SEED_MARKER "/data/adb/ksu/.seed"
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
static bool read_packages_list(struct list_head *uid_list)
{
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
            break;
        }
        buf[count] = '\0';

        struct uid_data *data = kzalloc(sizeof(struct uid_data), GFP_KERNEL);
        if (!data) {
            filp_close(fp, 0);
            return false;
        }

        char *tmp = buf;
        const char *delim = " ";
        char *package = strsep(&tmp, delim);
        char *uid = strsep(&tmp, delim);
        if (!uid || !package) {
            kfree(data);
            pr_err("update_uid: package or uid is NULL!\n");
            break;
        }

        u32 res;
        if (kstrtou32(uid, 10, &res)) {
            kfree(data);
            pr_err("update_uid: uid parse err\n");
            break;
        }
        data->uid = res;
        strscpy(data->package, package, sizeof(data->package));
        list_add_tail(&data->list, uid_list);
        // reset line start
        line_start = pos;
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

// Caller holds ksu_cred. Runs from post-fs-data on: the first boot after flashing has no
// /data/adb/ksud yet, so boot-completed is never reported. packages.list from the previous
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

void ksu_pkg_tracker_update(void)
{
    struct list_head uid_list;
    struct uid_data *np, *n;
    const struct cred *old_cred = override_creds(ksu_cred);

    INIT_LIST_HEAD(&uid_list);
    if (read_packages_list(&uid_list)) {
        ksu_seed_apply(&uid_list);
        ksu_prune_allowlist(is_uid_exist, &uid_list);
    }

    list_for_each_entry_safe (np, n, &uid_list, list) {
        list_del(&np->list);
        kfree(np);
    }
    revert_creds(old_cred);
}
