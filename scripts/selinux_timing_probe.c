// Probe for scripts/device_selinux_timing_test.sh and scripts/device_selinux_hide_pending_test.sh
// (run as root on the device).
//
// Becomes what Duck Detector's App Zygote carrier is (u:r:app_zygote:s0, an app uid) and then:
//   time <cpu> <pairs>  writes Duck's payloads to /proc/thread-self/attr/current in AB/BA pairs
//                       on one pinned CPU: A names a type no policy has, B is A with a leading
//                       newline; both must fail with EINVAL. Prints the A-B trimmed mean (middle
//                       80% of the sorted pair deltas), the lower median and how often A was slower.
//   write <context>     one write, then the context the thread has afterwards.
//   status              reads /sys/fs/selinux/status, the page the hook may fake for apps.
//   rootstatus          reads that page as root instead, which the hook never fakes.
//   sidtab <node> <n>   checks n new contexts through selinuxfs (node context|access) and
//                       reports how far the live sidtab grew; stock grows it, so must the hook.
//   selfattr <context>  sets the context via lsm_set_self_attr(2), the path not covered by the
//                       setprocattr hook (needs -DCAM_NR_LSM_SET_SELF_ATTR=460 if the libc lacks it).
//
// app_zygote may not write to the adb terminal: run it with its output into a pipe (`| cat`).
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#define ATTR "/proc/thread-self/attr/current"
#define STATUS "/sys/fs/selinux/status"
#define SIDTAB_STATS "/sys/fs/selinux/ss/sidtab_hash_stats"
#define WARMUP 32

static const char payload_a[] = "u:r:__selinux_hide_probe_invalid__:s0";
static char payload_b[sizeof(payload_a)];
static int attr_fd;

static uint64_t now_ns(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC_RAW, &t);
    return (uint64_t)t.tv_sec * 1000000000ull + (uint64_t)t.tv_nsec;
}

static int64_t timed_write(const char *payload)
{
    uint64_t start = now_ns();
    ssize_t written = write(attr_fd, payload, sizeof(payload_a) - 1);
    int error = errno;
    uint64_t end = now_ns();
    if (written != -1 || error != EINVAL) {
        printf("error: write returned %zd, errno %d (want -1, EINVAL)\n", written, error);
        exit(3);
    }
    return (int64_t)(end - start);
}

static int compare(const void *x, const void *y)
{
    int64_t a = *(const int64_t *)x, b = *(const int64_t *)y;
    return (a > b) - (a < b);
}

static int become_carrier(void)
{
    int fd = open(ATTR, O_WRONLY);
    if (fd < 0 || write(fd, "u:r:app_zygote:s0", 17) != 17) {
        printf("error: cannot become u:r:app_zygote:s0: %s\n", strerror(errno));
        return -1;
    }
    close(fd);
    // An isolated-process uid: no app's allowlist entry or profile applies.
    if (setgroups(0, NULL) || setresgid(99999, 99999, 99999) || setresuid(99999, 99999, 99999)) {
        printf("error: cannot drop to uid 99999: %s\n", strerror(errno));
        return -1;
    }
    return 0;
}

static int run_time(int cpu, int pairs)
{
    cpu_set_t set;
    CPU_ZERO(&set);
    CPU_SET(cpu, &set);
    if (sched_setaffinity(0, sizeof(set), &set)) {
        printf("error: cannot pin to CPU %d: %s\n", cpu, strerror(errno));
        return 1;
    }
    attr_fd = open(ATTR, O_WRONLY);
    if (attr_fd < 0) {
        printf("error: open %s: %s\n", ATTR, strerror(errno));
        return 1;
    }
    memcpy(payload_b, payload_a, sizeof(payload_a));
    payload_b[0] = '\n';
    int64_t *delta = malloc(sizeof(*delta) * (size_t)pairs);
    if (!delta)
        return 1;
    int slower = 0;
    for (int i = -WARMUP; i < pairs; i++) {
        int64_t a, b;
        if (i & 1) {
            b = timed_write(payload_b);
            a = timed_write(payload_a);
        } else {
            a = timed_write(payload_a);
            b = timed_write(payload_b);
        }
        if (i < 0)
            continue;
        delta[i] = a - b;
        slower += a > b;
    }
    qsort(delta, (size_t)pairs, sizeof(*delta), compare);
    double sum = 0;
    int low = pairs / 10, high = pairs - pairs / 10;
    for (int i = low; i < high; i++)
        sum += (double)delta[i];
    printf("cpu=%d pairs=%d mean=%.1f median=%lld slower=%d\n", sched_getcpu(), pairs, sum / (high - low),
           (long long)delta[(pairs - 1) / 2], slower);
    return 0;
}

static int run_write(const char *context)
{
    int fd = open(ATTR, O_WRONLY);
    ssize_t written = write(fd, context, strlen(context));
    int error = written < 0 ? errno : 0;
    char current[256] = { 0 };
    int in = open(ATTR, O_RDONLY);
    if (in < 0 || read(in, current, sizeof(current) - 1) < 0)
        strcpy(current, "?");
    current[strcspn(current, "\n")] = 0;
    printf("written=%zd errno=%d now=%s\n", written, error, current);
    return 0;
}

static int run_status(void)
{
    // struct selinux_kernel_status: version, sequence, enforcing, policyload, deny_unknown
    uint32_t status[5] = { 0 };
    int fd = open(STATUS, O_RDONLY);
    if (fd < 0 || read(fd, status, sizeof(status)) != (ssize_t)sizeof(status)) {
        printf("error: read %s: %s\n", STATUS, strerror(errno));
        return 1;
    }
    close(fd);
    printf("sequence=%u enforcing=%u policyload=%u\n", status[1], status[2], status[3]);
    return 0;
}

// live sidtab size, read from ss/sidtab_hash_stats ("entries: <n>")
static long sidtab_entries(void)
{
    char buf[512] = { 0 };
    int fd = open(SIDTAB_STATS, O_RDONLY);
    if (fd < 0 || read(fd, buf, sizeof(buf) - 1) < 0) {
        printf("error: read %s: %s\n", SIDTAB_STATS, strerror(errno));
        return -1;
    }
    close(fd);
    char *p = strstr(buf, "entries:");
    return p ? strtol(p + 8, NULL, 10) : -1;
}

// Check <count> never-seen valid contexts through a selinuxfs node and report how
// far the live sidtab grew. Stock adds one entry per new context; the hook must
// too (it parses the backup but still syncs the live policy), or an app reads the
// difference. node is "context" or "access".
static int run_sidtab(const char *node, int count)
{
    int access_node = !strcmp(node, "access");
    char path[64];
    snprintf(path, sizeof(path), "/sys/fs/selinux/%s", node);
    srand((unsigned)time(NULL) ^ (unsigned)getpid());
    long before = sidtab_entries();
    int ok = 0, err = 0;
    for (int i = 0; i < count; i++) {
        char ctx[96], payload[192], out[256];
        int a = rand() % 1000, b = a + 1 + rand() % 23;
        snprintf(ctx, sizeof(ctx), "u:object_r:app_data_file:s0:c%d,c%d", a, b);
        if (access_node)
            snprintf(payload, sizeof(payload), "u:r:untrusted_app:s0 %s 1", ctx);
        else
            snprintf(payload, sizeof(payload), "%s", ctx);
        int fd = open(path, O_RDWR);
        if (fd < 0) {
            err++;
            continue;
        }
        ssize_t w = write(fd, payload, strlen(payload));
        if (w >= 0 && read(fd, out, sizeof(out) - 1) >= 0)
            ok++;
        else
            err++;
        close(fd);
    }
    long after = sidtab_entries();
    printf("node=%s ok=%d err=%d before=%ld after=%ld grew=%ld\n", node, ok, err, before, after, after - before);
    return 0;
}

#if defined(__NR_lsm_set_self_attr) || defined(CAM_NR_LSM_SET_SELF_ATTR)
#ifndef __NR_lsm_set_self_attr
#define __NR_lsm_set_self_attr CAM_NR_LSM_SET_SELF_ATTR
#endif
#define CAM_LSM_ATTR_CURRENT 100
#define CAM_LSM_ID_SELINUX 101
struct cam_lsm_ctx {
    uint64_t id, flags, len, ctx_len;
    unsigned char ctx[];
};

// Set the thread's context through lsm_set_self_attr(2), the path that does not
// go through setprocattr. Reports errno and the resulting context, like `write`.
static int run_selfattr(const char *context)
{
    size_t clen = strlen(context) + 1;
    size_t total = sizeof(struct cam_lsm_ctx) + clen;
    struct cam_lsm_ctx *c = calloc(1, total);
    c->id = CAM_LSM_ID_SELINUX;
    c->len = total;
    c->ctx_len = clen;
    memcpy(c->ctx, context, clen);
    long rc = syscall(__NR_lsm_set_self_attr, (unsigned)CAM_LSM_ATTR_CURRENT, c, (unsigned)total, 0u);
    int error = rc < 0 ? errno : 0;
    free(c);
    char current[256] = { 0 };
    int in = open(ATTR, O_RDONLY);
    if (in < 0 || read(in, current, sizeof(current) - 1) < 0)
        strcpy(current, "?");
    current[strcspn(current, "\n")] = 0;
    printf("rc=%ld errno=%d now=%s\n", rc, error, current);
    return 0;
}
#endif

int main(int argc, char **argv)
{
    if (argc == 4 && !strcmp(argv[1], "time")) {
        int pairs = atoi(argv[3]);
        if (pairs < 10)
            return 2;
        return become_carrier() ? 1 : run_time(atoi(argv[2]), pairs);
    }
    if (argc == 3 && !strcmp(argv[1], "write"))
        return become_carrier() ? 1 : run_write(argv[2]);
    if (argc == 2 && !strcmp(argv[1], "status"))
        return become_carrier() ? 1 : run_status();
    if (argc == 2 && !strcmp(argv[1], "rootstatus"))
        return run_status();
    if (argc == 4 && !strcmp(argv[1], "sidtab")) {
        int count = atoi(argv[3]);
        if (count < 1)
            return 2;
        return become_carrier() ? 1 : run_sidtab(argv[2], count);
    }
#if defined(__NR_lsm_set_self_attr) || defined(CAM_NR_LSM_SET_SELF_ATTR)
    if (argc == 3 && !strcmp(argv[1], "selfattr"))
        return become_carrier() ? 1 : run_selfattr(argv[2]);
#endif
    fprintf(stderr,
            "usage: %s time <cpu> <pairs> | write <context> | status | rootstatus | "
            "sidtab <context|access> <count> | selfattr <context>\n",
            argv[0]);
    return 2;
}
