// Just enough of the kernel API for policy/pkg_tracker.c to build and run on the host.
// Every linux/*.h, ksu.h, klog.h and policy/allowlist.h under tests/stub includes only this.
// The harness (tests/pkg_tracker_test.c) supplies the functions declared at the bottom.
#ifndef KSU_TEST_KSTUB_H
#define KSU_TEST_KSTUB_H

#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>

// loff_t comes from glibc's sys/types.h
typedef uint32_t u32;

#define KSU_MAX_PACKAGE_NAME 256
#define PER_USER_RANGE 100000

#define GFP_KERNEL 0
#define kzalloc(size, gfp) calloc(1, size)
#define kfree(p) free(p)
#define kstrdup(s, gfp) strdup(s)
#define strscpy(dst, src, size) ((void)snprintf(dst, size, "%s", src))
#define module_param_named(name, var, type, perm)

#define ERR_PTR(err) ((void *)(intptr_t)(err))
#define PTR_ERR(p) ((long)(intptr_t)(p))
#define MAX_ERRNO 4095
#define IS_ERR(p) ((uintptr_t)(p) >= UINTPTR_MAX - MAX_ERRNO + 1)

struct cred {
    int unused;
};
extern struct cred *ksu_cred;
#define override_creds(c) ((const struct cred *)(c))
#define revert_creds(c) ((void)(c))

struct file {
    int fd;
};

struct list_head {
    struct list_head *next, *prev;
};

static inline void INIT_LIST_HEAD(struct list_head *h)
{
    h->next = h->prev = h;
}

static inline void list_add_tail(struct list_head *n, struct list_head *h)
{
    n->prev = h->prev;
    n->next = h;
    h->prev->next = n;
    h->prev = n;
}

static inline void list_del(struct list_head *e)
{
    e->prev->next = e->next;
    e->next->prev = e->prev;
}

#define list_entry(ptr, type, member) ((type *)((char *)(ptr) - offsetof(type, member)))
#define list_for_each_entry(pos, head, member)                                                                         \
    for (pos = list_entry((head)->next, __typeof__(*pos), member); &pos->member != (head);                             \
         pos = list_entry(pos->member.next, __typeof__(*pos), member))
#define list_for_each_entry_safe(pos, n, head, member)                                                                 \
    for (pos = list_entry((head)->next, __typeof__(*pos), member),                                                     \
        n = list_entry(pos->member.next, __typeof__(*pos), member);                                                    \
         &pos->member != (head); pos = n, n = list_entry(n->member.next, __typeof__(*n), member))

void test_log(const char *fmt, ...);
#define pr_err(...) test_log(__VA_ARGS__)
#define pr_warn(...) test_log(__VA_ARGS__)
#define pr_info(...) test_log(__VA_ARGS__)

struct file *filp_open(const char *path, int flags, int mode);
void filp_close(struct file *f, void *id);
ssize_t kernel_read(struct file *f, void *buf, size_t count, loff_t *pos);
ssize_t kernel_write(struct file *f, const void *buf, size_t count, loff_t *pos);
int kstrtou32(const char *s, unsigned int base, u32 *res);

bool __ksu_is_allow_uid(uid_t uid);
void ksu_prune_allowlist(bool (*is_uid_exist)(uid_t, char *, void *), void *data);
void ksu_persistent_allow_list(void);
int ksu_grant_default_root(const char *package, uid_t uid);

#endif
