// Host tests for policy/pkg_tracker.c (the patch-time root seed, the Manager pin and its signature
// check) and policy/pkg_xml.c (reading signers out of packages.xml).
// Kernel file access goes to a scratch directory; allowlist calls are recorded.
// Run: make -C kernel/tests (needs gcc or clang with AddressSanitizer).
#include <assert.h>
#include <stdarg.h>
#include <stdio.h>
#include <sys/stat.h>
#include <unistd.h>

#include "../policy/pkg_tracker.c"
#include "../policy/pkg_xml.c"
#include "xml_builder.h"

static char root[512];
static struct cred ksu_cred_obj;
struct cred *ksu_cred = &ksu_cred_obj;

static char logbuf[16384];
// grants, revokes and prunes in call order, e.g. "grant com.termux:10234;prune;"
static char events[4096];
static uid_t allowed[64];
static int allowed_count;
static int persist_calls, prune_calls;
static int fail_grant;
int test_fail_shash;

// The certificate release Managers are signed with (public; from apksigner --print-certs-pem).
static uint8_t release_cert[2048];
static size_t release_cert_len;
// The offline backup key's certificate (KSU_MANAGER_CERT_BACKUP), exported with keytool -exportcert.
static uint8_t backup_cert[2048];
static size_t backup_cert_len;
static const uint8_t other_cert[] = { 0x30, 0x82, 0x01, 0x0a, 'i', 'm', 'p', 'o', 's', 't', 'o', 'r' };

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

loff_t test_file_size(struct file *f)
{
    struct stat st;
    assert(fstat(f->fd, &st) == 0);
    return st.st_size;
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

int ksu_revoke_root(const char *package, uid_t uid)
{
    char e[300];
    snprintf(e, sizeof(e), "revoke %s:%u;", package, uid);
    strcat(events, e);
    for (int i = 0; i < allowed_count; i++) {
        if (allowed[i] == uid)
            allowed[i--] = allowed[--allowed_count];
    }
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

static void load_release_cert(void)
{
    FILE *f = fopen("manager_cert.der", "rb");
    assert(f);
    release_cert_len = fread(release_cert, 1, sizeof(release_cert), f);
    fclose(f);
    assert(release_cert_len == 1261);
    f = fopen("manager_cert_backup.der", "rb");
    assert(f);
    backup_cert_len = fread(backup_cert, 1, sizeof(backup_cert), f);
    fclose(f);
    assert(backup_cert_len == 1275);
}

// How cam.su.kernel is signed in the packages.xml fixture.
enum mgr_sig {
    SIG_RELEASE, // release key, written in full here
    SIG_RELEASE_REUSED, // release key, first written under another package: index only
    SIG_OTHER, // some other key
    SIG_ROTATED, // other key now, release key only in <pastSigs>
    SIG_TWO_SIGNERS, // release key plus another
    SIG_BACKUP, // offline backup key
    SIG_ROTATED_TO_BACKUP, // backup key now, release key in <pastSigs> (APK v3 key rotation)
    SIG_NONE, // listed without <sigs>
    SIG_ABSENT, // not listed at depth 2; a nested decoy only
};

static void xml_package(struct xb *x, const char *name, uint32_t uid)
{
    xb_start(x, "package");
    xb_attr(x, "name", name);
    xb_attr(x, "codePath", "/data/app/~~x==/pkg-y==");
    xb_attr_interned(x, "primaryCpuAbi", "arm64-v8a");
    xb_attr_int(x, "publicFlags", 0x30c8be44);
    xb_attr_long_hex(x, "ft", 0x199d3b5a2c8ULL);
    xb_attr_int(x, "userId", uid);
    xb_attr_bool(x, "isOrphaned", true);
    xb_attr_float(x, "scale");
}

static void xml_sigs(struct xb *x, int count)
{
    xb_start(x, "sigs");
    xb_attr_int(x, "count", (uint32_t)count);
    xb_attr_int(x, "schemeVersion", 2);
}

static void build_packages_xml(struct xb *x, bool abx, enum mgr_sig sig)
{
    static const uint8_t termux_cert[] = { 0x30, 0x82, 0x00, 0x04, 't', 'e', 'r', 'm' };

    xb_begin(x, abx);
    xb_start(x, "packages");
    xb_start(x, "version");
    xb_attr_int(x, "sdkVersion", 34);
    xb_attr(x, "fingerprint", "google/x/y:14/AP1A/1:user/release-keys");
    xb_end(x, "version");
    xb_comment(x, " written by a test ");

    // cert 0
    xml_package(x, "com.termux", 10234);
    xml_sigs(x, 1);
    xb_cert(x, 0, termux_cert, sizeof(termux_cert));
    xb_end(x, "sigs");
    xb_start(x, "proper-signing-keyset");
    xb_attr_int(x, "identifier", 1);
    xb_end(x, "proper-signing-keyset");
    xb_end(x, "package");

    if (sig == SIG_RELEASE_REUSED) {
        // cert 1, written in full by the first package that has it
        xml_package(x, "com.example", 10300);
        xml_sigs(x, 1);
        xb_cert(x, 1, release_cert, release_cert_len);
        xb_end(x, "sigs");
        xb_end(x, "package");
    }

    if (sig == SIG_ABSENT) {
        xb_start(x, "shared-user");
        xb_attr(x, "name", "android.uid.system");
        xml_package(x, "cam.su.kernel", 10500);
        xml_sigs(x, 1);
        xb_cert(x, 1, release_cert, release_cert_len);
        xb_end(x, "sigs");
        xb_end(x, "package");
        xb_end(x, "shared-user");
    } else {
        xml_package(x, "cam.su.kernel", 10500);
        switch (sig) {
        case SIG_RELEASE:
            xml_sigs(x, 1);
            xb_cert(x, 1, release_cert, release_cert_len);
            xb_end(x, "sigs");
            break;
        case SIG_RELEASE_REUSED:
            xml_sigs(x, 1);
            xb_cert(x, 1, NULL, 0);
            xb_end(x, "sigs");
            break;
        case SIG_OTHER:
            xml_sigs(x, 1);
            xb_cert(x, 1, other_cert, sizeof(other_cert));
            xb_end(x, "sigs");
            break;
        case SIG_ROTATED:
            xml_sigs(x, 1);
            xb_cert(x, 1, other_cert, sizeof(other_cert));
            xb_start(x, "pastSigs");
            xb_attr_int(x, "count", 2);
            xb_cert(x, 2, release_cert, release_cert_len);
            xb_cert(x, 1, NULL, 0);
            xb_end(x, "pastSigs");
            xb_end(x, "sigs");
            break;
        case SIG_BACKUP:
            xml_sigs(x, 1);
            xb_cert(x, 1, backup_cert, backup_cert_len);
            xb_end(x, "sigs");
            break;
        case SIG_ROTATED_TO_BACKUP:
            xml_sigs(x, 1);
            xb_cert(x, 1, backup_cert, backup_cert_len);
            xb_start(x, "pastSigs");
            xb_attr_int(x, "count", 2);
            xb_cert(x, 2, release_cert, release_cert_len);
            xb_cert(x, 1, NULL, 0);
            xb_end(x, "pastSigs");
            xb_end(x, "sigs");
            break;
        case SIG_TWO_SIGNERS:
            xml_sigs(x, 2);
            xb_cert(x, 1, release_cert, release_cert_len);
            xb_cert(x, 2, other_cert, sizeof(other_cert));
            xb_end(x, "sigs");
            break;
        default:
            break;
        }
        xb_start(x, "perms");
        xb_start(x, "item");
        xb_attr(x, "name", "android.permission.INTERNET");
        xb_attr_bool(x, "granted", true);
        xb_end(x, "item");
        xb_end(x, "perms");
        xb_end(x, "package");
    }

    xb_start(x, "shared-user");
    xb_attr(x, "name", "android.uid.shell");
    xb_attr_int(x, "userId", 2000);
    xml_sigs(x, 1);
    xb_cert(x, 0, NULL, 0);
    xb_end(x, "sigs");
    xb_end(x, "shared-user");
    xb_end(x, "packages");
    xb_finish(x);
}

static struct xb fixture;

static void write_packages_xml(bool abx, enum mgr_sig sig)
{
    char p[1024];
    build_packages_xml(&fixture, abx, sig);
    map("/data/system/packages.xml", p);
    FILE *f = fopen(p, "wb");
    assert(f);
    assert(fwrite(fixture.buf, 1, fixture.len, f) == fixture.len);
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
    write_packages_xml(true, SIG_RELEASE);
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
    persist_calls = prune_calls = fail_grant = test_fail_shash = 0;
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

// ---- policy/pkg_xml.c ----

static uint8_t certbuf[8192];

static ssize_t find_in_fixture(bool abx, enum mgr_sig sig, const char *pkg)
{
    build_packages_xml(&fixture, abx, sig);
    return ksu_pkg_xml_find_cert(fixture.buf, fixture.len, pkg, certbuf, sizeof(certbuf));
}

static bool is_release(ssize_t n)
{
    return n == (ssize_t)release_cert_len && !memcmp(certbuf, release_cert, release_cert_len);
}

T(xml_finds_signer_written_in_place)
{
    for (int abx = 0; abx < 2; abx++)
        assert(is_release(find_in_fixture(abx, SIG_RELEASE, "cam.su.kernel")));
}

T(xml_finds_signer_written_under_another_package)
{
    for (int abx = 0; abx < 2; abx++)
        assert(is_release(find_in_fixture(abx, SIG_RELEASE_REUSED, "cam.su.kernel")));
}

T(xml_reports_other_signers_as_they_are)
{
    for (int abx = 0; abx < 2; abx++) {
        ssize_t n = find_in_fixture(abx, SIG_OTHER, "cam.su.kernel");
        assert(n == sizeof(other_cert) && !memcmp(certbuf, other_cert, sizeof(other_cert)));
        // a release key that was rotated away from does not count
        n = find_in_fixture(abx, SIG_ROTATED, "cam.su.kernel");
        assert(n == sizeof(other_cert) && !memcmp(certbuf, other_cert, sizeof(other_cert)));
    }
}

T(xml_errors)
{
    for (int abx = 0; abx < 2; abx++) {
        assert(find_in_fixture(abx, SIG_TWO_SIGNERS, "cam.su.kernel") == -ENOTUNIQ);
        assert(find_in_fixture(abx, SIG_NONE, "cam.su.kernel") == -ENODATA);
        assert(find_in_fixture(abx, SIG_ABSENT, "cam.su.kernel") == -ENOENT);
        assert(find_in_fixture(abx, SIG_RELEASE, "cam.su.kerne") == -ENOENT);
        assert(find_in_fixture(abx, SIG_RELEASE, "cam.su.kernel.evil") == -ENOENT);
        build_packages_xml(&fixture, abx, SIG_RELEASE);
        assert(ksu_pkg_xml_find_cert(fixture.buf, fixture.len, "cam.su.kernel", certbuf, release_cert_len - 1) ==
               -E2BIG);
        assert(ksu_pkg_xml_find_cert(fixture.buf, fixture.len, "cam.su.kernel", certbuf, release_cert_len) ==
               (ssize_t)release_cert_len);
    }
    assert(ksu_pkg_xml_find_cert((const u8 *)"", 0, "cam.su.kernel", certbuf, sizeof(certbuf)) == -ENOENT);
    assert(ksu_pkg_xml_find_cert((const u8 *)"ABX\0", 4, "cam.su.kernel", certbuf, sizeof(certbuf)) == -EBADMSG);
}

T(xml_text_from_older_android)
{
    // Signature.toCharsString wrote lower-case hex before attributeBytesHex
    const char *xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                      "<!DOCTYPE packages>\n<packages>\n"
                      "<package name='cam.su.kernel' userId=\"10500\">\n"
                      "  <sigs count=\"1\">\n    <cert index = \"7\" key=\"30820a0bFF\"/>\n  </sigs>\n"
                      "</package>\n</packages>\n";
    ssize_t n = ksu_pkg_xml_find_cert((const u8 *)xml, strlen(xml), "cam.su.kernel", certbuf, sizeof(certbuf));
    assert(n == 5 && !memcmp(certbuf, "\x30\x82\x0a\x0b\xff", 5));

    const char *bad[] = {
        "<packages><package name=\"cam.su.kernel\"><sigs><cert index=\"1\" key=\"30F\"/></sigs></package></packages>",
        "<packages><package name=\"cam.su.kernel\"><sigs><cert index=\"1\" key=\"zz\"/></sigs></package></packages>",
        "<packages><package name=\"cam.su.kernel\"><sigs><cert key=\"3082\"/></sigs></package></packages>",
        "<packages><package name=\"cam.su.kernel\"><sigs><cert index=\"x\" key=\"3082\"/></sigs></package></packages>",
        "<packages><package name=\"cam.su.kernel\"><sigs><cert index=\"1\"/></sigs></package></packages>",
        "<packages><package name=\"cam.su.kernel\"><sigs><cert index=\"1\" key=\"\"/></sigs></package></packages>",
        "<packages><package name=\"cam.su.kernel\"><sigs><cert index=\"1\" key=\"3082\"/></sigs>",
        "<packages><package name=\"cam.su.kernel\" <sigs>",
        "<packages><package name=\"cam.su.kernel\"><sigs><cert index=\"1\" key=\"3082/></sigs></package></packages>",
    };
    for (size_t i = 0; i < sizeof(bad) / sizeof(bad[0]); i++)
        assert(ksu_pkg_xml_find_cert((const u8 *)bad[i], strlen(bad[i]), "cam.su.kernel", certbuf, sizeof(certbuf)) ==
               -EBADMSG);
}

// Every cut-off point and single-byte corruption: never a read out of bounds (ASan), and a
// certificate is only ever reported if it is one the file really holds for the Manager.
T(xml_truncated_or_corrupt_never_misreads)
{
    static struct xb copy;
    for (int abx = 0; abx < 2; abx++) {
        build_packages_xml(&fixture, abx, SIG_RELEASE_REUSED);
        for (size_t len = 0; len < fixture.len; len++) {
            uint8_t *cut = malloc(len ? len : 1);
            memcpy(cut, fixture.buf, len);
            ssize_t n = ksu_pkg_xml_find_cert(cut, len, "cam.su.kernel", certbuf, sizeof(certbuf));
            assert(n < 0 || is_release(n));
            free(cut);
        }
        unsigned seed = 1;
        for (int round = 0; round < 20000; round++) {
            copy = fixture;
            seed = seed * 1103515245 + 12345;
            size_t at = (seed >> 8) % fixture.len;
            copy.buf[at] ^= (uint8_t)(1 + (seed >> 24) % 255);
            (void)ksu_pkg_xml_find_cert(copy.buf, copy.len, "cam.su.kernel", certbuf, sizeof(certbuf));
        }
    }
}

// ---- Manager pin signature check ----

static void boot_manager(bool abx, enum mgr_sig sig)
{
    char pkgs[1024];
    snprintf(pkgs, sizeof(pkgs), "%s%s", PKGS, MANAGER);
    boot_with(NULL, pkgs, true);
    write_packages_xml(abx, sig);
}

T(manager_pin_trusts_release_key_in_either_format)
{
    for (int abx = 0; abx < 2; abx++) {
        boot_manager(abx, SIG_RELEASE);
        ksu_pkg_tracker_update();
        assert(strcmp(events, "grant cam.su.kernel:10500;prune;") == 0);
        boot_manager(abx, SIG_RELEASE_REUSED);
        ksu_pkg_tracker_update();
        assert(strcmp(events, "grant cam.su.kernel:10500;prune;") == 0);
    }
}

// Losing the release key must not strand flashed kernels: the backup key gets the pin too.
T(manager_pin_trusts_backup_key)
{
    enum mgr_sig sigs[] = { SIG_BACKUP, SIG_ROTATED_TO_BACKUP };
    for (int abx = 0; abx < 2; abx++) {
        for (size_t i = 0; i < sizeof(sigs) / sizeof(sigs[0]); i++) {
            boot_manager(abx, sigs[i]);
            ksu_pkg_tracker_update();
            assert(strcmp(events, "grant cam.su.kernel:10500;prune;") == 0);
        }
    }
}

T(manager_pin_refuses_impostor)
{
    enum mgr_sig sigs[] = { SIG_OTHER, SIG_ROTATED, SIG_TWO_SIGNERS };
    for (size_t i = 0; i < sizeof(sigs) / sizeof(sigs[0]); i++) {
        boot_manager(true, sigs[i]);
        ksu_pkg_tracker_update();
        assert(strcmp(events, "prune;") == 0 && persist_calls == 0);
    }
    boot_manager(true, SIG_OTHER);
    ksu_pkg_tracker_update();
    // the log names the certificate that was refused
    assert(strstr(logbuf, "manager pin: cam.su.kernel is signed by untrusted certificate "));
    assert(!strstr(logbuf, KSU_MANAGER_CERT));
}

T(manager_pin_revokes_impostor_on_reused_appid)
{
    boot_manager(true, SIG_RELEASE);
    ksu_pkg_tracker_update();
    assert(__ksu_is_allow_uid(10500));
    // Manager uninstalled, impostor installed under the same appid before a prune could run
    write_packages_xml(true, SIG_OTHER);
    reboot_keep_fs();
    ksu_pkg_tracker_update();
    assert(strcmp(events, "revoke cam.su.kernel:10500;prune;") == 0);
    assert(!__ksu_is_allow_uid(10500) && persist_calls == 1);
    assert(strstr(logbuf, "manager pin: revoked cam.su.kernel(10500)"));
    // nothing more to take away
    reboot_keep_fs();
    ksu_pkg_tracker_update();
    assert(strcmp(events, "prune;") == 0 && persist_calls == 0);
}

T(manager_pin_unknown_keeps_state)
{
    // could not tell: no grant, and an existing grant is left alone
    boot_manager(true, SIG_RELEASE);
    sh("rm -f '%s/data/system/packages.xml'", root);
    ksu_pkg_tracker_update();
    assert(strcmp(events, "prune;") == 0);
    assert(strstr(logbuf, "manager pin: cannot check cam.su.kernel(10500)"));

    boot_manager(true, SIG_NONE);
    ksu_pkg_tracker_update();
    assert(strcmp(events, "prune;") == 0);

    boot_manager(true, SIG_RELEASE);
    test_fail_shash = 1;
    ksu_pkg_tracker_update();
    assert(strcmp(events, "prune;") == 0);

    boot_manager(true, SIG_RELEASE);
    ksu_pkg_tracker_update();
    write_file("/data/system/packages.xml", "");
    reboot_keep_fs();
    ksu_pkg_tracker_update();
    assert(strcmp(events, "prune;") == 0 && __ksu_is_allow_uid(10500));
}

T(manager_seed_entry_goes_through_the_pin)
{
    char pkgs[1024];
    snprintf(pkgs, sizeof(pkgs), "%s%s", PKGS, MANAGER);
    boot_with(NONCE ",com.termux:10234,cam.su.kernel:10500", pkgs, true);
    ksu_pkg_tracker_update();
    assert(strcmp(events, "grant com.termux:10234;grant cam.su.kernel:10500;prune;") == 0);
    assert(strstr(logbuf, "seed: skip cam.su.kernel: the manager pin checks its signature"));

    boot_with(NONCE ",com.termux:10234,cam.su.kernel:10500", pkgs, true);
    write_packages_xml(true, SIG_OTHER);
    ksu_pkg_tracker_update();
    assert(strcmp(events, "grant com.termux:10234;prune;") == 0);
}

int main(void)
{
    snprintf(root, sizeof(root), "/tmp/pkg-tracker-test-%d", getpid());
    load_release_cert();
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
    RUN(xml_finds_signer_written_in_place);
    RUN(xml_finds_signer_written_under_another_package);
    RUN(xml_reports_other_signers_as_they_are);
    RUN(xml_errors);
    RUN(xml_text_from_older_android);
    RUN(xml_truncated_or_corrupt_never_misreads);
    RUN(manager_pin_trusts_release_key_in_either_format);
    RUN(manager_pin_trusts_backup_key);
    RUN(manager_pin_refuses_impostor);
    RUN(manager_pin_revokes_impostor_on_reused_appid);
    RUN(manager_pin_unknown_keeps_state);
    RUN(manager_seed_entry_goes_through_the_pin);
    printf("%d passed\n", passed);
    sh("rm -rf '%s'", root);
    return 0;
}
