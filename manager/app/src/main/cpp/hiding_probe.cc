// Raw syscalls for the root hiding probe (cam.su.kernel.hiding.NativeProbe).
//
// A module that hides root inside an app often hooks libc (open, read, stat...). These
// go straight to the kernel, so the probe can compare them with what libc answers: a
// difference means something in the app rewrites libc results. Loaded only by the
// isolated hiding probe process.

#include <jni.h>

#include <cerrno>
#include <cstdint>
#include <ctime>
#include <fcntl.h>
#include <string>
#include <sys/syscall.h>
#include <unistd.h>
#include <vector>

namespace {

long raw_syscall(long nr, long a0, long a1, long a2, long a3) {
#if defined(__aarch64__)
    register long x8 asm("x8") = nr;
    register long x0 asm("x0") = a0;
    register long x1 asm("x1") = a1;
    register long x2 asm("x2") = a2;
    register long x3 asm("x3") = a3;
    asm volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3) : "memory", "cc");
    return x0;
#elif defined(__x86_64__)
    long ret;
    register long r10 asm("r10") = a3;
    asm volatile("syscall"
                 : "=a"(ret)
                 : "a"(nr), "D"(a0), "S"(a1), "d"(a2), "r"(r10)
                 : "rcx", "r11", "memory");
    return ret;
#elif defined(__riscv)
    register long a7 asm("a7") = nr;
    register long r0 asm("a0") = a0;
    register long r1 asm("a1") = a1;
    register long r2 asm("a2") = a2;
    register long r3 asm("a3") = a3;
    asm volatile("ecall" : "+r"(r0) : "r"(a7), "r"(r1), "r"(r2), "r"(r3) : "memory");
    return r0;
#else
    // no inline syscall for this ABI: libc's wrapper, which a hook could still reach
    long ret = syscall(nr, a0, a1, a2, a3);
    return ret < 0 ? -errno : ret;
#endif
}

// 0 when the path exists, else the (positive) errno
int raw_access(const char *path) {
    long ret = raw_syscall(__NR_faccessat, AT_FDCWD, reinterpret_cast<long>(path), F_OK, 0);
    return ret < 0 ? static_cast<int>(-ret) : 0;
}

bool raw_read(const char *path, std::string &out) {
    long fd = raw_syscall(__NR_openat, AT_FDCWD, reinterpret_cast<long>(path), O_RDONLY | O_CLOEXEC, 0);
    if (fd < 0) return false;
    char buf[8192];
    for (;;) {
        long n = raw_syscall(__NR_read, fd, reinterpret_cast<long>(buf), sizeof(buf), 0);
        if (n == -EINTR) continue;
        if (n <= 0) break;
        out.append(buf, static_cast<size_t>(n));
    }
    raw_syscall(__NR_close, fd, 0, 0, 0);
    return true;
}

// --- attr/current timing (Duck Detector's SELinux hide probe) ---
//
// An app may not change its own SELinux context, so writing to attr/current fails with
// EACCES whatever it writes. A hook that parses the context before that permission check
// makes a valid context (A) slower to refuse than one starting with a newline (B), which
// stock SELinux never parses. /proc/thread-self is needed: attr/current of another thread
// refuses writes before any SELinux code runs.

constexpr char kAttrCurrent[] = "/proc/thread-self/attr/current";
constexpr int kWarmupPairs = 32;
constexpr int kTimedPairs = 256;

// Failure codes returned instead of samples; keep in step with AttrTiming.kt.
constexpr jlong kNoAttr = -1;       // attr/current cannot be opened or read
constexpr jlong kNotAppDomain = -2; // not an app SELinux context
constexpr jlong kNotRefused = -3;   // a write was not refused with EACCES: stop, never retry

int64_t now_ns() {
    timespec t{};
    clock_gettime(CLOCK_MONOTONIC_RAW, &t);
    return static_cast<int64_t>(t.tv_sec) * 1000000000LL + t.tv_nsec;
}

// Nanoseconds the refused write took, or -1 when it was not refused with EACCES.
int64_t timed_write(const std::string &payload) {
    long fd = raw_syscall(__NR_openat, AT_FDCWD, reinterpret_cast<long>(kAttrCurrent), O_WRONLY | O_CLOEXEC, 0);
    if (fd < 0) return -1;
    int64_t before = now_ns();
    long ret = raw_syscall(__NR_write, fd, reinterpret_cast<long>(payload.data()), static_cast<long>(payload.size()), 0);
    int64_t after = now_ns();
    raw_syscall(__NR_close, fd, 0, 0, 0);
    return ret == -EACCES ? after - before : -1;
}

// A and B times, interleaved, or one failure code.
std::vector<jlong> attr_timing() {
    std::string a;
    if (!raw_read(kAttrCurrent, a)) return {kNoAttr};
    while (!a.empty() && (a.back() == '\n' || a.back() == '\0')) a.pop_back();
    if (a.size() < 5 || a.find('\0') != std::string::npos || a.rfind("u:r:", 0) != 0) return {kNotAppDomain};
    // same length, only the first byte differs
    std::string b = a;
    b[0] = '\n';

    // both must be refused before timing anything: never keep writing a context the
    // kernel might accept
    if (timed_write(b) < 0 || timed_write(a) < 0) return {kNotRefused};

    std::vector<jlong> out;
    out.reserve(kTimedPairs * 2);
    for (int i = -kWarmupPairs; i < kTimedPairs; ++i) {
        // alternate the order so neither side always runs with a warmer cache
        int64_t ta, tb;
        if ((i & 1) == 0) {
            ta = timed_write(a);
            tb = timed_write(b);
        } else {
            tb = timed_write(b);
            ta = timed_write(a);
        }
        if (ta < 0 || tb < 0) return {kNotRefused};
        if (i < 0) continue;
        out.push_back(ta);
        out.push_back(tb);
    }
    return out;
}

}  // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_cam_su_kernel_hiding_NativeProbe_attrTiming0(JNIEnv *env, jclass) {
    // never from a process that could hold setcurrent
    std::vector<jlong> samples = getuid() < 10000 ? std::vector<jlong>{kNotAppDomain} : attr_timing();
    jlongArray out = env->NewLongArray(static_cast<jsize>(samples.size()));
    if (out != nullptr) env->SetLongArrayRegion(out, 0, static_cast<jsize>(samples.size()), samples.data());
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_cam_su_kernel_hiding_NativeProbe_read0(JNIEnv *env, jclass, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    std::string data;
    bool ok = raw_read(path, data);
    env->ReleaseStringUTFChars(jpath, path);
    if (!ok) return nullptr;
    // proc files are text; drop any byte NewStringUTF would choke on
    for (char &c : data) {
        if (static_cast<unsigned char>(c) >= 0x80 || c == '\0') c = '?';
    }
    return env->NewStringUTF(data.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_cam_su_kernel_hiding_NativeProbe_access0(JNIEnv *env, jclass, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    int err = raw_access(path);
    env->ReleaseStringUTFChars(jpath, path);
    return err;
}
