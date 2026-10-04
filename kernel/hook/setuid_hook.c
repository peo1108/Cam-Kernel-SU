#include <linux/compiler.h>
#include <linux/version.h>
#include <linux/slab.h>
#include <linux/task_work.h>
#include <linux/thread_info.h>
#include <linux/seccomp.h>
#include <linux/printk.h>
#include <linux/sched.h>
#include <linux/sched/signal.h>
#include <linux/string.h>
#include <linux/types.h>
#include <linux/uaccess.h>
#include <linux/uidgid.h>
#include <linux/workqueue.h>
#include <linux/susfs_def.h>
#include "selinux/selinux.h"

#include "policy/allowlist.h"
#include "hook/setuid_hook.h"
#include "klog.h" // IWYU pragma: keep
#include "infra/seccomp_cache.h"
#include "feature/kernel_umount.h"

extern u32 susfs_zygote_sid;
extern u32 susfs_zygote_next_sid;
extern void disable_seccomp(void);
extern struct work_struct susfs_extra_works;

static inline void ksu_handle_extra_susfs_work(void)
{
    if (work_pending(&susfs_extra_works))
        return;

    schedule_work(&susfs_extra_works);
}

// The manager has no kernel identity in this fork (uid 0 is the manager),
// so a root-granted app is treated like any other allowed uid here.
static int handle_zygote_setresuid(uid_t ruid, bool zygote_next)
{
    // Isolated services are always umounted
    if (is_isolated_process(ruid))
        goto do_umount;

    // Normal user apps that the allowlist says must be umounted
    if (likely(is_appuid(ruid) && ksu_uid_should_umount(ruid)))
        goto do_umount;

    // Running "su" disables seccomp anyway, so do it up front for root apps
    if (ksu_is_allow_uid_for_current(ruid)) {
        disable_seccomp();
        return 0;
    }

    // Not umounted, but root is not allowed either
    susfs_set_current_proc_no_su();
    return 0;

do_umount:
    susfs_set_current_proc_no_su();
    susfs_set_current_proc_umounted();
    if (zygote_next) {
        // zygote_next still runs in the init namespace, so do not umount here
        susfs_set_current_proc_umounted_for_zygote_next();
    } else {
        ksu_handle_umount(current_uid().val, ruid);
    }
    ksu_handle_extra_susfs_work();
    return 0;
}

int ksu_handle_setresuid(uid_t ruid, uid_t euid, uid_t suid)
{
    if (current_uid().val != 0)
        return 0;

    // Only processes spawned by zygote or zygote_next are interesting
    if (susfs_is_sid_equal(current_cred(), susfs_zygote_sid))
        return handle_zygote_setresuid(ruid, false);

    if (susfs_is_sid_equal(current_cred(), susfs_zygote_next_sid))
        return handle_zygote_setresuid(ruid, true);

    return 0;
}

void __init ksu_setuid_hook_init(void)
{
    ksu_kernel_umount_init();
}

void __exit ksu_setuid_hook_exit(void)
{
    pr_info("ksu_core_exit\n");
    ksu_kernel_umount_exit();
}
