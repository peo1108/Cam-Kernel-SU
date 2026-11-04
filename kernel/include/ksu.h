#ifndef __KSU_H_KSU
#define __KSU_H_KSU

#include <linux/types.h>
#include <linux/cred.h>
#include <linux/workqueue.h>

#define KERNEL_SU_VERSION KSU_VERSION

extern struct cred *ksu_cred;
extern bool ksu_late_loaded;
extern bool allow_shell;
#ifdef MODULE
extern bool ksu_bundled;
#endif
extern struct selinux_policy *backup_sepolicy;
// The live policy KSU last published and its seqno (kernel/selinux/rules.c), set while every
// policy since the backup came from KSU; read by selinux_hide (live_policy_is_derived).
extern struct selinux_policy *ksu_derived_policy;
extern u32 ksu_derived_seqno;
extern bool ksu_no_custom_rc;

static inline int startswith(char *s, char *prefix)
{
    return strncmp(s, prefix, strlen(prefix));
}

static inline int endswith(const char *s, const char *t)
{
    size_t slen = strlen(s);
    size_t tlen = strlen(t);
    if (tlen > slen)
        return 1;
    return strcmp(s + slen - tlen, t);
}

#endif
