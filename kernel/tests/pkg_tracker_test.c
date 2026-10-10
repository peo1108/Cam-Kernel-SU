// Host tests for policy/pkg_tracker.c: the patch-time root seed and the Manager pin.
// Kernel file access goes to a scratch directory; allowlist calls are recorded.
// Run: make -C kernel/tests (needs gcc or clang with AddressSanitizer).
#include <assert.h>
#include <stdarg.h>
#include <stdio.h>
#include <unistd.h>

#include "../policy/pkg_tracker.c"

static char root[512];
static struct cred ksu_cred_obj;
struct cred *ksu_cred = &ksu_cred_obj;

static char logbuf[16384];
// grants and prunes in call order, e.g. "grant com.termux:10234;prune;"
static char events[4096];
static uid_t allowed[64];
static int allowed_count;
static int persist_calls, prune_calls;
static int fail_grant;

void test_log(const char *fmt, ...)
{
    char line[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);
    strncat(logbuf, line, sizeof(logbuf) - strlen(logbuf) - 1);
}

static void map(const char *path, char *out)
{
    snprintf(out, 1024, "%s%s", root, path);
}

struct file *filp_open(const char *path, int flags, int mode)
{
    char p[1024];
    map(path, p);
    int fd = open(p, flags, mode);
    if (fd < 0)
        return ERR_PTR(-errno);
    struct file *f = malloc(sizeof(*f));
    f->fd = fd;
    return f;
}

void filp_close(struct file *f, void *id)
{
    (void)id;
    close(f->fd);
    free(f);
}

ssize_t kernel_read(struct file *f, void *buf, size_t count, loff_t *pos)
{
    ssize_t r = pread(f->fd, buf, count, *pos);
    if (r > 0)
        *pos += r;
    return r;
}

ssize_t kernel_write(struct file *f, const void *buf, size_t count, loff_t *pos)
{
    ssize_t r = pwrite(f->fd, buf, count, *pos);
    if (r > 0)
        *pos += r;
    return r;
}

// Same acceptance as the kernel's kstrtou32 for base 10: digits, optional single trailing '\n'.
int kstrtou32(const char *s, unsigned int base, u32 *res)
{
    unsigned long long v = 0;
    const char *p = s;
    (void)base;
    if (*p == '+')
        p++;
    const char *start = p;
    for (; *p >= '0' && *p <= '9'; p++) {
        v = v * 10 + (unsigned)(*p - '0');
        if (v > 0xffffffffULL)
            return -ERANGE;
    }
    if (p == start)
        return -EINVAL;
    if (*p == '\n')
        p++;
    if (*p)
        return -EINVAL;
    *res = (u32)v;
    return 0;
}

bool __ksu_is_allow_uid(uid_t uid)
{
    for (int i = 0; i < allowed_count; i++) {
        if (allowed[i] == uid)
            return true;
    }
    return false;
}

void ksu_prune_allowlist(bool (*is_uid_exist)(uid_t, char *, void *), void *data)
{
    (void)is_uid_exist;
    (void)data;
    prune_calls++;
    strcat(events, "prune;");
}

void ksu_persistent_allow_list(void)
{
    persist_calls++;
}

int ksu_grant_default_root(const char *package, uid_t uid)
{
    char e[300];
    if (fail_grant)
        return -12;
    snprintf(e, sizeof(e), "grant %s:%u;", package, uid);
    strcat(events, e);
    allowed[allowed_count++] = uid;
    return 0;
}

static void sh(const char *fmt, const char *arg)
{
    char cmd[1200];
    snprintf(cmd, sizeof(cmd), fmt, arg, arg, arg);
    assert(system(cmd) == 0);
}

static void write_file(const char *path, const char *content)
{
    char p[1024];
    map(path, p);
    FILE *f = fopen(p, "w");
    assert(f);
    fputs(content, f);
    fclose(f);
}

static int read_marker(char *out)
{
    char p[1024];
    map(KSU_SEED_MARKER, p);
    FILE *f = fopen(p, "r");
    if (!f)
        return -1;
    size_t n = fread(out, 1, 63, f);
    out[n] = 0;
    fclose(f);
    return (int)n;
}

// No Manager here: the pin would add its own grant to every seed test.
static const char *PKGS = "com.termux 10234 0 /data/user/0/com.termux default:targetSdkVersion=28 3003 0 1\n"
                          "com.example 10300 0 /data/user/0/com.example default 3003 0 1\n"
                          "com.other 10400 0 /data/user/0/com.other default 3003 0 1\n";

static char seedbuf[8192];

// A fresh boot: new scratch tree, empty allowlist, packages.list = pkgs.
static void boot_with(const char *seed, const char *pkgs, bool with_cam_dir)
{
    sh("rm -rf '%s' && mkdir -p '%s/data/system' '%s/data/adb'", root);
    write_file("/data/system/packages.list", pkgs);
    if (with_cam_dir)
        sh("mkdir -p '%s/data/adb/cam'", root);
    if (seed) {
        strcpy(seedbuf, seed);
        ksu_seed = seedbuf;
    } else {
        ksu_seed = NULL;
    }
    ksu_seed_done = false;
    logbuf[0] = events[0] = 0;
    allowed_count = 0;
    persist_calls = prune_calls = fail_grant = 0;
}

static void boot(const char *seed, bool with_cam_dir)
{
    boot_with(seed, PKGS, with_cam_dir);
}

// Reboot keeping files and the allowlist.
static void reboot_keep_fs(void)
{
    ksu_seed_done = false;
    logbuf[0] = events[0] = 0;
    persist_calls = prune_calls = 0;
}

#define NONCE "0123456789abcdef"
static int passed;
#define T(name) static void name(void)
#define RUN(name)                                                                                                      \
    do {                                                                                                               \
        name();                                                                                                        \
        printf("PASS %s\n", #name);                                                                                    \
        passed++;                                                                                                      \
    } while (0)

T(grants_matching_seed_and_writes_marker)
{
    char m[64];
    boot(NONCE ",com.termux:10234,com.example:10300", true);
    ksu_pkg_tracker_update();
    assert(strcmp(events, "grant com.termux:10234;grant com.example:10300;prune;") == 0);
    assert(persist_calls == 1 && prune_calls == 1);
    assert(read_marker(m) == 16 && strcmp(m, NONCE) == 0);
    assert(strstr(logbuf, "seed: granted com.termux(10234)"));
    // the module parameter itself is left as it was
    assert(strcmp(ksu_seed, NONCE ",com.termux:10234,com.example:10300") == 0);
}

T(second_update_same_boot_does_not_regrant)
{
    boot(NONCE ",com.termux:10234", false); // marker cannot be written
    ksu_pkg_tracker_update();
    events[0] = 0;
    ksu_pkg_tracker_update();
    assert(strcmp(events, "prune;") == 0);
    assert(prune_calls == 2);
}

T(same_nonce_after_reboot_is_already_applied)
{
    boot(NONCE ",com.termux:10234", true);
    ksu_pkg_tracker_update();
    reboot_keep_fs();
    ksu_pkg_tracker_update();
    assert(strcmp(events, "prune;") == 0 && persist_calls == 0);
    assert(strstr(logbuf, "seed: nonce 0123456789abcdef already applied"));
}

T(new_nonce_after_reboot_applies_again)
{
    char m[64];
    boot(NONCE ",com.termux:10234", true);
    ksu_pkg_tracker_update();
    reboot_keep_fs();
    strcpy(seedbuf, "fedcba9876543210,com.other:10400");
    ksu_pkg_tracker_update();
    assert(strcmp(events, "grant com.other:10400;prune;") == 0);
    assert(read_marker(m) == 16 && strcmp(m, "fedcba9876543210") == 0);
}

T(uid_mismatch_or_not_installed_is_skipped)
{
    boot(NONCE ",com.termux:10999,com.missing:10500,com.other:10400", true);
    ksu_pkg_tracker_update();
    assert(strcmp(events, "grant com.other:10400;prune;") == 0);
    assert(strstr(logbuf, "seed: skip com.termux(10999): not installed or uid mismatch"));
    assert(strstr(logbuf, "seed: skip com.missing(10500)"));
}

T(bad_nonce_rejects_whole_seed)
{
    char m[64];
    const char *bad[] = { "zz,com.termux:10234",
                          "0123456789ABCDEF,com.termux:10234",
                          "0123456789abcde,com.termux:10234",
                          "0123456789abcdef0,com.termux:10234",
                          ",com.termux:10234",
                          "com.termux:10234" };
    for (size_t i = 0; i < sizeof(bad) / sizeof(bad[0]); i++) {
        boot(bad[i], true);
        ksu_pkg_tracker_update();
        assert(strcmp(events, "prune;") == 0);
        assert(read_marker(m) < 0);
    }
}

T(entry_count_limits)
{
    char s[8192];
    boot(NONCE, true);
    ksu_pkg_tracker_update();
    assert(strcmp(events, "prune;") == 0 && strstr(logbuf, "invalid entry count 0"));
    strcpy(s, NONCE);
    for (int i = 0; i < 33; i++)
        strcat(s, ",com.termux:10234");
    boot(s, true);
    ksu_pkg_tracker_update();
    assert(strcmp(events, "prune;") == 0 && strstr(logbuf, "invalid entry count 33"));
    strcpy(s, NONCE);
    for (int i = 0; i < 32; i++)
        strcat(s, ",com.termux:10234");
    boot(s, true);
    ksu_pkg_tracker_update();
    assert(strncmp(events, "grant com.termux:10234;", 23) == 0);
}

T(invalid_entries_skipped_valid_kept)
{
    char longpkg[400];
    char s[1024];
    memset(longpkg, 'a', 256);
    longpkg[256] = 0;
    snprintf(s, sizeof(s),
             NONCE ",a-b:10001,com.termux,:10234,com.x:9999,com.x:20000,com.x:1x,com.x:,com.x:+10234,%s:10001,,"
                   "com.termux:10234",
             longpkg);
    boot(s, true);
    ksu_pkg_tracker_update();
    assert(strcmp(events, "grant com.termux:10234;prune;") == 0);
}

T(missing_cam_dir_grants_and_retries_marker)
{
    char m[64];
    boot(NONCE ",com.termux:10234", false);
    ksu_pkg_tracker_update();
    assert(strcmp(events, "grant com.termux:10234;prune;") == 0);
    assert(read_marker(m) < 0);
    assert(strstr(logbuf, "will retry next boot"));
    // next boot, the directory exists: applied again and the marker written
    sh("mkdir -p '%s/data/adb/cam'", root);
    reboot_keep_fs();
    ksu_pkg_tracker_update();
    assert(strcmp(events, "grant com.termux:10234;prune;") == 0);
    assert(read_marker(m) == 16);
}

T(no_seed_grants_nothing)
{
    boot(NULL, true);
    ksu_pkg_tracker_update();
    assert(strcmp(events, "prune;") == 0);
    boot("", true);
    ksu_pkg_tracker_update();
    assert(strcmp(events, "prune;") == 0);
}

T(grant_failure_does_not_persist)
{
    char m[64];
    boot(NONCE ",com.termux:10234", true);
    fail_grant = 1;
    ksu_pkg_tracker_update();
    assert(persist_calls == 0 && strstr(logbuf, "seed: grant com.termux(10234) failed"));
    assert(read_marker(m) == 16);
}

T(missing_packages_list_does_nothing)
{
    boot(NONCE ",com.termux:10234", true);
    sh("rm -f '%s/data/system/packages.list'", root);
    ksu_pkg_tracker_update();
    assert(events[0] == 0 && prune_calls == 0);
}

static const char *MANAGER = "cam.su.kernel 10500 0 /data/user/0/cam.su.kernel default 3003 0 1\n";

T(manager_is_pinned_by_name)
{
    char pkgs[1024];
    snprintf(pkgs, sizeof(pkgs), "%s%s", PKGS, MANAGER);
    boot_with(NULL, pkgs, true);
    ksu_pkg_tracker_update();
    assert(strcmp(events, "grant cam.su.kernel:10500;prune;") == 0);
    assert(persist_calls == 1);
    // already allowed: no second grant, nothing written
    reboot_keep_fs();
    ksu_pkg_tracker_update();
    assert(strcmp(events, "prune;") == 0 && persist_calls == 0);
}

T(manager_pin_runs_after_seed_before_prune)
{
    char pkgs[1024];
    snprintf(pkgs, sizeof(pkgs), "%s%s", PKGS, MANAGER);
    boot_with(NONCE ",com.termux:10234", pkgs, true);
    ksu_pkg_tracker_update();
    assert(strcmp(events, "grant com.termux:10234;grant cam.su.kernel:10500;prune;") == 0);
}

T(reinstalled_manager_gets_its_new_appid)
{
    boot_with(NULL, MANAGER, true);
    ksu_pkg_tracker_update();
    write_file("/data/system/packages.list", "cam.su.kernel 10600 0 /data/user/0/cam.su.kernel default 3003 0 1\n");
    reboot_keep_fs();
    ksu_pkg_tracker_update();
    assert(strcmp(events, "grant cam.su.kernel:10600;prune;") == 0);
}

T(manager_pin_needs_exact_name_and_app_range)
{
    const char *cases[] = {
        "cam.su.kernel.evil 10501 0 /data/user/0/x default 3003 0 1\n",
        "cam.su.kerne 10502 0 /data/user/0/x default 3003 0 1\n",
        "cam.su.kernel 1000 0 /data/system default 3003 0 1\n",
        "cam.su.kernel 20000 0 /data/user/0/x default 3003 0 1\n",
    };
    for (size_t i = 0; i < sizeof(cases) / sizeof(cases[0]); i++) {
        boot_with(NULL, cases[i], true);
        ksu_pkg_tracker_update();
        assert(strcmp(events, "prune;") == 0);
    }
}

T(manager_pin_failure_is_logged)
{
    boot_with(NULL, MANAGER, true);
    fail_grant = 1;
    ksu_pkg_tracker_update();
    assert(persist_calls == 0 && strstr(logbuf, "manager pin: grant cam.su.kernel(10500) failed"));
}

int main(void)
{
    snprintf(root, sizeof(root), "/tmp/pkg-tracker-test-%d", getpid());
    RUN(grants_matching_seed_and_writes_marker);
    RUN(second_update_same_boot_does_not_regrant);
    RUN(same_nonce_after_reboot_is_already_applied);
    RUN(new_nonce_after_reboot_applies_again);
    RUN(uid_mismatch_or_not_installed_is_skipped);
    RUN(bad_nonce_rejects_whole_seed);
    RUN(entry_count_limits);
    RUN(invalid_entries_skipped_valid_kept);
    RUN(missing_cam_dir_grants_and_retries_marker);
    RUN(no_seed_grants_nothing);
    RUN(grant_failure_does_not_persist);
    RUN(missing_packages_list_does_nothing);
    RUN(manager_is_pinned_by_name);
    RUN(manager_pin_runs_after_seed_before_prune);
    RUN(reinstalled_manager_gets_its_new_appid);
    RUN(manager_pin_needs_exact_name_and_app_range);
    RUN(manager_pin_failure_is_logged);
    printf("%d passed\n", passed);
    sh("rm -rf '%s'", root);
    return 0;
}
