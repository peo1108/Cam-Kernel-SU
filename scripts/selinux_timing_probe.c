// Probe for scripts/device_selinux_timing_test.sh and scripts/device_selinux_hide_pending_test.sh
// (run as root on the device).
//
// Becomes what Duck Detector's App Zygote carrier is (u:r:app_zygote:s0, an app uid) and then:
//   time <cpu> <pairs>  writes Duck's payloads to /proc/thread-self/attr/current in AB/BA pairs
//                       on one pinned CPU: A names a type no policy has, B is A with a leading
//                       newline; both must fail with EINVAL. Prints the A-B trimmed mean (middle
//                       80% of the sorted pair deltas), the lower median and how often A was slower.
//   duck <mode> <runs>  the same payloads the way Duck's App Zygote timing takes them (nightly
//                       e0a57418): not pinned, 32 warmups and 512 pairs, a pair dropped only when
//                       the CPU changes between its two writes; each run gets Duck's verdict, and
//                       the last line the trimmed mean of every kept pair of every run.
//                       mode free leaves the CPU to the scheduler, hop moves to the next CPU
//                       every 32 pairs (one run spans every CPU class), split moves in the
//                       middle of every 4th pair (128 dropped: Duck's partial-sample path).
//                       This carrier is not Duck's preload: on a TB323FU stock reads A-B >= 0
//                       here and -53..-104 ns in Duck, so only hook against stock means much.
//   jostle <name> <us> <s>  as root: waits up to s seconds for a new process named <name> (Duck's
//                       com.eltavine.duckdetector_zygote, whose preload takes the real sample),
//                       then moves its main thread to the next CPU every <us> microseconds until
//                       it exits, so Duck itself measures across CPU changes. Prints the moves.
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
#include <dirent.h>
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
#define DUCK_PAIRS 512
#define DUCK_BLOCK 32

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

// the next CPU after `cpu` this process may run on, wrapping around
static int next_cpu(const cpu_set_t *allowed, int cpu)
{
    for (int i = 1; i <= CPU_SETSIZE; i++) {
        int next = (cpu + i) % CPU_SETSIZE;
        if (CPU_ISSET(next, allowed))
            return next;
    }
    return cpu;
}

// pinning to a CPU the thread is not on moves it there before this returns
static void move_to(int cpu)
{
    cpu_set_t set;
    CPU_ZERO(&set);
    CPU_SET(cpu, &set);
    sched_setaffinity(0, sizeof(set), &set);
}

// Duck's median(): the lower middle of the sorted values
static int64_t lower_median(const int64_t *values, int n, int64_t *scratch)
{
    memcpy(scratch, values, sizeof(*values) * (size_t)n);
    qsort(scratch, (size_t)n, sizeof(*scratch), compare);
    return scratch[(n - 1) / 2];
}

// timingVerdict in Duck's SelinuxAvcLookupReading.kt (b63d6f62): a full sample goes by the sign
// of the median; one with dropped pairs is Positive only when the drops were all CPU changes,
// at least 256 pairs are left, the median is positive and A was slower in 90% of them.
static const char *duck_verdict(int pairs, int migrations, int slower, int64_t median)
{
    if (pairs == DUCK_PAIRS && migrations == 0)
        return median > 0 ? "positive" : median < 0 ? "negative" : "baseline";
    if (migrations > 0 && pairs + migrations == DUCK_PAIRS && pairs >= 256 && median > 0 &&
        (long)slower * 10 >= (long)pairs * 9)
        return "positive";
    return "inconclusive";
}

static int run_duck(const char *mode, int runs)
{
    int hop = !strcmp(mode, "hop"), split = !strcmp(mode, "split");
    if (!hop && !split && strcmp(mode, "free"))
        return 2;
    cpu_set_t allowed;
    if (sched_getaffinity(0, sizeof(allowed), &allowed)) {
        printf("error: sched_getaffinity: %s\n", strerror(errno));
        return 1;
    }
    attr_fd = open(ATTR, O_WRONLY);
    if (attr_fd < 0) {
        printf("error: open %s: %s\n", ATTR, strerror(errno));
        return 1;
    }
    memcpy(payload_b, payload_a, sizeof(payload_a));
    payload_b[0] = '\n';
    static int64_t delta[DUCK_PAIRS], scratch[DUCK_PAIRS];
    int64_t *kept = malloc(sizeof(*kept) * DUCK_PAIRS * (size_t)runs);
    if (!kept)
        return 1;
    int total = 0;
    int positive = 0, negative = 0, baseline = 0, inconclusive = 0;
    int cpu = sched_getcpu();
    for (int run = 0; run < runs; run++) {
        if (hop || split) {
            cpu = next_cpu(&allowed, cpu);
            move_to(cpu);
        }
        int pairs = 0, migrations = 0, slower = 0;
        cpu_set_t seen;
        CPU_ZERO(&seen);
        for (int i = -WARMUP; i < DUCK_PAIRS; i++) {
            if (hop && i > 0 && i % DUCK_BLOCK == 0) {
                cpu = next_cpu(&allowed, cpu);
                move_to(cpu);
            }
            int moves = split && i >= 0 && i % 4 == 3;
            int before = sched_getcpu();
            int64_t a, b;
            if (i & 1) {
                b = timed_write(payload_b);
                if (moves)
                    move_to(cpu = next_cpu(&allowed, cpu));
                a = timed_write(payload_a);
            } else {
                a = timed_write(payload_a);
                if (moves)
                    move_to(cpu = next_cpu(&allowed, cpu));
                b = timed_write(payload_b);
            }
            if (i < 0)
                continue;
            int after = sched_getcpu();
            if (before < 0 || after < 0 || before != after) {
                migrations++;
                continue;
            }
            CPU_SET(before, &seen);
            kept[total++] = a - b;
            delta[pairs++] = a - b;
            slower += a > b;
        }
        int up = 0, down = 0, zero = 0;
        for (int k = 0; k + DUCK_BLOCK <= pairs; k += DUCK_BLOCK) {
            int64_t block = lower_median(delta + k, DUCK_BLOCK, scratch);
            up += block > 0;
            down += block < 0;
            zero += block == 0;
        }
        int64_t median = pairs ? lower_median(delta, pairs, scratch) : 0;
        const char *verdict = pairs ? duck_verdict(pairs, migrations, slower, median) : "inconclusive";
        positive += !strcmp(verdict, "positive");
        negative += !strcmp(verdict, "negative");
        baseline += !strcmp(verdict, "baseline");
        inconclusive += !strcmp(verdict, "inconclusive");
        printf("run=%d cpus=%d pairs=%d migrations=%d median=%lld slower=%d blocks=%d/%d/%d verdict=%s\n", run,
               CPU_COUNT(&seen), pairs, migrations, (long long)median, slower, up, down, zero, verdict);
    }
    qsort(kept, (size_t)total, sizeof(*kept), compare);
    double sum = 0;
    int low = total / 10, high = total - total / 10;
    for (int i = low; i < high; i++)
        sum += (double)kept[i];
    printf("duck mode=%s runs=%d mean=%.1f positive=%d negative=%d baseline=%d inconclusive=%d\n", mode, runs,
           high > low ? sum / (high - low) : 0.0, positive, negative, baseline, inconclusive);
    free(kept);
    return 0;
}

// a process above `after` whose cmdline is `name`, or 0
static pid_t find_process(const char *name, pid_t after)
{
    DIR *proc = opendir("/proc");
    pid_t found = 0;
    struct dirent *entry;
    while (proc && !found && (entry = readdir(proc))) {
        pid_t pid = (pid_t)atoi(entry->d_name);
        if (pid <= after)
            continue;
        char path[64], cmdline[128] = { 0 };
        snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
        int fd = open(path, O_RDONLY);
        if (fd < 0)
            continue;
        if (read(fd, cmdline, sizeof(cmdline) - 1) > 0 && !strcmp(cmdline, name))
            found = pid;
        close(fd);
    }
    if (proc)
        closedir(proc);
    return found;
}

static int run_jostle(const char *name, int interval_us, int seconds)
{
    pid_t newest = 0;
    DIR *proc = opendir("/proc");
    struct dirent *entry;
    while (proc && (entry = readdir(proc)))
        if (atoi(entry->d_name) > newest)
            newest = (pid_t)atoi(entry->d_name);
    if (proc)
        closedir(proc);
    cpu_set_t allowed;
    CPU_ZERO(&allowed);
    for (int cpu = 0; cpu < (int)sysconf(_SC_NPROCESSORS_CONF) && cpu < CPU_SETSIZE; cpu++)
        CPU_SET(cpu, &allowed);
    uint64_t deadline = now_ns() + (uint64_t)seconds * 1000000000ull;
    pid_t pid = 0;
    while (!(pid = find_process(name, newest))) {
        if (now_ns() > deadline) {
            printf("error: no new %s within %d s\n", name, seconds);
            return 1;
        }
        usleep(200);
    }
    int cpu = 0, moves = 0, failed = 0;
    uint64_t start = now_ns();
    for (;;) {
        cpu = next_cpu(&allowed, cpu);
        cpu_set_t set;
        CPU_ZERO(&set);
        CPU_SET(cpu, &set);
        if (sched_setaffinity(pid, sizeof(set), &set)) {
            if (errno == ESRCH)
                break;
            failed++;
        } else {
            moves++;
        }
        for (uint64_t until = now_ns() + (uint64_t)interval_us * 1000u; now_ns() < until;)
            ;
    }
    printf("pid=%d moves=%d failed=%d for %.1f ms\n", pid, moves, failed, (double)(now_ns() - start) / 1e6);
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
    if (argc == 4 && !strcmp(argv[1], "duck")) {
        int runs = atoi(argv[3]);
        if (runs < 1)
            return 2;
        return become_carrier() ? 1 : run_duck(argv[2], runs);
    }
    if (argc == 5 && !strcmp(argv[1], "jostle"))
        return run_jostle(argv[2], atoi(argv[3]), atoi(argv[4]));
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
            "usage: %s time <cpu> <pairs> | duck <free|hop|split> <runs> | jostle <name> <us> <s> | "
            "write <context> | status | rootstatus | "
            "sidtab <context|access> <count> | selfattr <context>\n",
            argv[0]);
    return 2;
}
