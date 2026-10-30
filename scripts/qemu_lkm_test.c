// Runs as /init.real after caminit loaded camsu.ko, see scripts/qemu_y700_lkm.sh.
// Goes through the supercall and the syscall hooks, then powers the VM off.
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/reboot.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>
#include <linux/types.h>
#include "uapi/supercall.h"

static const int EXEC_CHILD_RC = 7;

static void print_file(const char *path, const char *match)
{
    char buf[8192];
    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        printf("CT %s: cannot open\n", path);
        return;
    }
    int n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    buf[n > 0 ? n : 0] = 0;
    for (char *line = strtok(buf, "\n"); line; line = strtok(NULL, "\n")) {
        if (!match || strstr(line, match))
            printf("CT %s: %s\n", path, line);
    }
}

static void exec_child(void)
{
    char *argv[] = { "/init.real", "child", NULL };
    execve(argv[0], argv, environ);
    _exit(1);
}

static void test_supercall(int *ksu_fd)
{
    struct ksu_get_info_cmd info = { 0 };
    unsigned int safemode = 0;

    // Returns -1 on purpose: the kprobe puts the fd in *ksu_fd and fails the reboot itself
    syscall(SYS_reboot, KSU_INSTALL_MAGIC1, KSU_INSTALL_MAGIC2, 0, ksu_fd);
    printf("CT supercall fd=%d\n", *ksu_fd);
    if (*ksu_fd < 0)
        return;
    long rc = ioctl(*ksu_fd, KSU_IOCTL_GET_INFO, &info);
    printf("CT GET_INFO rc=%ld version=%u flags=0x%x uapi=%u\n", rc, info.version, info.flags, info.uapi_version);
    rc = ioctl(*ksu_fd, KSU_IOCTL_CHECK_SAFEMODE, &safemode);
    printf("CT SAFEMODE rc=%ld value=%u\n", rc, safemode);
}

static void test_syscall_hooks(void)
{
    struct stat st;
    int ok = 0;

    for (int i = 0; i < 3000; i++) {
        faccessat(AT_FDCWD, "/system/bin/su", F_OK, 0);
        if (!fstatat(AT_FDCWD, "/init.real", &st, 0))
            ok++;
    }
    printf("CT faccessat+newfstatat ok=%d/3000\n", ok);

    ok = 0;
    for (int i = 0; i < 200; i++) {
        int status = 0;
        pid_t pid = fork();
        if (!pid)
            exec_child();
        waitpid(pid, &status, 0);
        if (WIFEXITED(status) && WEXITSTATUS(status) == EXEC_CHILD_RC)
            ok++;
    }
    printf("CT execve ok=%d/200\n", ok);

    // uid >= 10000 is what the setresuid hook treats as an app
    int status = 0;
    pid_t pid = fork();
    if (!pid) {
        long rc = setresuid(10123, 10123, 10123);
        printf("CT setresuid rc=%ld uid=%d\n", rc, getuid());
        exec_child();
    }
    waitpid(pid, &status, 0);
    printf("CT app child rc=%d\n", WEXITSTATUS(status));
}

static void test_grant_root(int ksu_fd)
{
    int status = 0;
    pid_t pid = fork();
    if (!pid) {
        long rc = ioctl(ksu_fd, KSU_IOCTL_GRANT_ROOT, 0);
        printf("CT GRANT_ROOT rc=%ld uid=%d\n", rc, getuid());
        _exit(0);
    }
    waitpid(pid, &status, 0);
    printf("CT grant child rc=%d signal=%d\n", WEXITSTATUS(status), WTERMSIG(status));
}

int main(int argc, char **argv)
{
    int ksu_fd = -1;

    if (argc > 1 && !strcmp(argv[1], "child"))
        _exit(EXEC_CHILD_RC);

    setvbuf(stdout, NULL, _IONBF, 0);
    mount("proc", "/proc", "proc", 0, NULL);
    mount("sysfs", "/sys", "sysfs", 0, NULL);
    print_file("/proc/version", NULL);
    print_file("/proc/modules", "camsu");
    test_supercall(&ksu_fd);
    test_syscall_hooks();
    if (ksu_fd >= 0)
        test_grant_root(ksu_fd);
    print_file("/proc/sys/kernel/tainted", NULL);
    printf("CT DONE\n");
    sync();
    reboot(RB_POWER_OFF);
    return 0;
}
