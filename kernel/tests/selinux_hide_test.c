// Host tests for the context parsing in feature/selinux_hide.c (string_to_context_struct and
// security_context_to_sid_with_policy, taken verbatim from the module by extract_fn.awk): a context
// the backup policy rejects never reaches the live policy (one parse, like stock: Duck times that
// path, row 71 of docs/CAM_CHANGES.md); an accepted one is synced into the live sidtab; a live
// sidtab frozen by a policy reload (-ESTALE) is retried on the new policy (upstream #3936).
// The policy, sidtab and MLS calls are fakes that count what the code does.
// Run: make -C kernel/tests (needs gcc or clang with AddressSanitizer).
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef uint32_t u32;
typedef unsigned int gfp_t;
#define GFP_KERNEL 0
#define SECSID_NULL 0
#define kfree(p) free(p)

// One datum layout for users, roles and types.
struct type_datum {
    u32 value;
    bool attribute;
};
#define role_datum type_datum
#define user_datum type_datum

struct symtab {
    const char *names[8];
    struct type_datum datums[8];
    int lookups;
};

struct policydb {
    const char *label;
    struct symtab p_users, p_roles, p_types;
    int mls_calls;
    char mls_seen[64]; // MLS string of the last mls_context_to_sid call
};

struct sidtab {
    u32 base;
    int inserts; // calls that got a SID
    int fail_rc;
    struct selinux_policy *reloaded; // non-NULL: frozen, the reload publishes this policy
};

struct selinux_policy {
    struct sidtab *sidtab;
    struct policydb policydb;
};

struct context {
    u32 user, role, type;
    void *range; // stands in for the MLS ebitmaps: allocated, so ASan sees leaks and double frees
};

static struct {
    struct selinux_policy *policy;
} selinux_state;

static int rcu_depth, rcu_locks, kmemdup_calls;

static void rcu_read_lock(void)
{
    assert(rcu_depth == 0);
    rcu_depth++;
    rcu_locks++;
}

static void rcu_read_unlock(void)
{
    assert(rcu_depth == 1);
    rcu_depth--;
}

#define rcu_dereference(p) (p)

static char *kmemdup_nul(const char *s, size_t len, gfp_t gfp)
{
    char *p = malloc(len + 1);
    kmemdup_calls++;
    memcpy(p, s, len);
    p[len] = 0;
    return p;
}

static void *symtab_search(struct symtab *s, const char *name)
{
    assert(rcu_depth == 1);
    s->lookups++;
    for (int i = 0; i < 8 && s->names[i]; i++)
        if (!strcmp(s->names[i], name))
            return &s->datums[i];
    return NULL;
}

static void context_init(struct context *c)
{
    memset(c, 0, sizeof(*c));
}

static void context_destroy(struct context *c)
{
    free(c->range);
    c->range = NULL;
}

// Takes "s0" with optional categories; mutates the string like the kernel's strsep parsing.
static int mls_context_to_sid(struct policydb *p, char oldc, char *scontext, struct context *c, struct sidtab *s,
                              u32 def_sid)
{
    assert(rcu_depth == 1);
    p->mls_calls++;
    if (!oldc) {
        p->mls_seen[0] = 0;
        return -EINVAL; // no MLS part and no default SID
    }
    snprintf(p->mls_seen, sizeof(p->mls_seen), "%s", scontext);
    if (strncmp(scontext, "s0", 2))
        return -EINVAL;
    for (char *q = scontext; *q; q++)
        if (*q == ':' || *q == ',' || *q == '-')
            *q = 0;
    c->range = malloc(1);
    return 0;
}

static int policydb_context_isvalid(struct policydb *p, struct context *c)
{
    return c->user && c->role && c->type;
}

static int sidtab_context_to_sid(struct sidtab *s, struct context *c, u32 *sid)
{
    assert(rcu_depth == 1);
    if (s->reloaded) {
        selinux_state.policy = s->reloaded;
        return -ESTALE;
    }
    if (s->fail_rc)
        return s->fail_rc;
    s->inserts++;
    *sid = s->base + c->type; // the same context always gets the same SID
    return 0;
}

#include "selinux_hide_parse.inc"

// The stock policy: what apps must keep seeing.
static const char *const users[] = { "u", NULL };
static const char *const roles[] = { "r", "object_r", NULL };
static const char *const stock_types[] = { "untrusted_app", "app_zygote", "domain", NULL };
// The live policy adds KernelSU's domain.
static const char *const live_types[] = { "untrusted_app", "app_zygote", "domain", "ksu", NULL };

static void fill(struct symtab *s, const char *const *names, const char *attribute)
{
    memset(s, 0, sizeof(*s));
    for (int i = 0; names[i]; i++) {
        s->names[i] = names[i];
        s->datums[i].value = i + 1;
        s->datums[i].attribute = attribute && !strcmp(names[i], attribute);
    }
}

static struct sidtab backup_sidtab, live_sidtab, reloaded_sidtab;
static struct selinux_policy backup, live, reloaded;

static void make_policy(struct selinux_policy *pol, struct sidtab *st, const char *label, const char *const *types,
                        u32 base)
{
    memset(pol, 0, sizeof(*pol));
    memset(st, 0, sizeof(*st));
    pol->sidtab = st;
    pol->policydb.label = label;
    fill(&pol->policydb.p_users, users, NULL);
    fill(&pol->policydb.p_roles, roles, NULL);
    fill(&pol->policydb.p_types, types, "domain");
    st->base = base;
}

static void reset(void)
{
    make_policy(&backup, &backup_sidtab, "backup", stock_types, 1000);
    make_policy(&live, &live_sidtab, "live", live_types, 2000);
    make_policy(&reloaded, &reloaded_sidtab, "reloaded", live_types, 3000);
    selinux_state.policy = &live;
    rcu_depth = rcu_locks = kmemdup_calls = 0;
}

static int lookups(struct policydb *p)
{
    return p->p_users.lookups + p->p_roles.lookups + p->p_types.lookups;
}

struct result {
    int rc, orig_rc;
    u32 sid, orig_sid;
};

// Calls like my_setprocattr: with the live SID and rc.
static struct result to_sid(const char *ctx)
{
    struct result r = { .orig_rc = 12345, .sid = 777, .orig_sid = 777 };
    char copy[128];
    snprintf(copy, sizeof(copy), "%s", ctx);
    r.rc = security_context_to_sid_with_policy(&backup, copy, strlen(copy), &r.sid, SECSID_NULL, GFP_KERNEL,
                                               &r.orig_sid, &r.orig_rc);
    assert(!strcmp(copy, ctx)); // the caller's buffer is never touched
    assert(rcu_depth == 0);
    return r;
}

static int passed;
#define T(name) static void name(void)
#define RUN(name)                                                                                                      \
    do {                                                                                                               \
        reset();                                                                                                       \
        name();                                                                                                        \
        printf("PASS %s\n", #name);                                                                                    \
        passed++;                                                                                                      \
    } while (0)

T(rejected_type_costs_one_parse_like_stock)
{
    // Duck's probe A: a type no policy has
    struct result r = to_sid("u:r:duck_missing_t:s0");
    assert(r.rc == -EINVAL && r.orig_rc == -EINVAL);
    assert(r.sid == SECSID_NULL && r.orig_sid == SECSID_NULL);
    assert(lookups(&backup.policydb) == 3 && backup.policydb.mls_calls == 0);
    assert(lookups(&live.policydb) == 0 && live.policydb.mls_calls == 0);
    assert(kmemdup_calls == 1 && rcu_locks == 1);
    assert(backup_sidtab.inserts == 0 && live_sidtab.inserts == 0);
}

T(rejected_user_stops_at_first_lookup)
{
    struct result r = to_sid("nobody:r:untrusted_app:s0");
    assert(r.rc == -EINVAL && r.orig_rc == -EINVAL);
    assert(backup.policydb.p_users.lookups == 1 && lookups(&backup.policydb) == 1);
    assert(lookups(&live.policydb) == 0);
}

T(rejected_mls_never_reaches_live)
{
    struct result r = to_sid("u:r:untrusted_app:s9:c1");
    assert(r.rc == -EINVAL && r.orig_rc == -EINVAL);
    assert(backup.policydb.mls_calls == 1 && live.policydb.mls_calls == 0 && lookups(&live.policydb) == 0);
}

T(attribute_is_not_a_type)
{
    struct result r = to_sid("u:r:domain:s0");
    assert(r.rc == -EINVAL && lookups(&live.policydb) == 0);
}

T(live_only_context_stays_hidden)
{
    // KernelSU's own domain is in the live policy only: apps must see stock's EINVAL
    struct result r = to_sid("u:r:ksu:s0");
    assert(r.rc == -EINVAL && r.orig_rc == -EINVAL && r.orig_sid == SECSID_NULL);
    assert(lookups(&live.policydb) == 0 && live_sidtab.inserts == 0);
}

T(accepted_context_syncs_live_sidtab)
{
    struct result r = to_sid("u:r:untrusted_app:s0:c1,c2");
    assert(r.rc == 0 && r.orig_rc == 0);
    assert(r.sid == 1001 && r.orig_sid == 2001);
    assert(backup_sidtab.inserts == 1 && live_sidtab.inserts == 1);
    // both policies parse the whole string: the copy is restored after the backup mutated it
    assert(!strcmp(backup.policydb.mls_seen, "s0:c1,c2") && !strcmp(live.policydb.mls_seen, "s0:c1,c2"));
    assert(lookups(&backup.policydb) == 3 && lookups(&live.policydb) == 3);
    assert(kmemdup_calls == 1 && rcu_locks == 1);
}

T(context_without_mls_is_rejected)
{
    struct result r = to_sid("u:r:untrusted_app");
    assert(r.rc == -EINVAL && live.policydb.mls_calls == 0);
}

T(live_rejection_leaves_orig_sid_null)
{
    // a type the backup has and the live policy lost
    live.policydb.p_types.names[0] = "renamed_app";
    struct result r = to_sid("u:r:untrusted_app:s0");
    assert(r.rc == 0 && r.sid == 1001);
    assert(r.orig_rc == -EINVAL && r.orig_sid == SECSID_NULL && live_sidtab.inserts == 0);
}

T(stale_live_sidtab_retries_on_reloaded_policy)
{
    live_sidtab.reloaded = &reloaded;
    struct result r = to_sid("u:r:untrusted_app:s0:c5");
    assert(r.rc == 0 && r.orig_rc == 0);
    assert(r.sid == 1001 && r.orig_sid == 3001);
    assert(rcu_locks == 2);
    // the second pass parses an intact string again on both sides
    assert(backup.policydb.mls_calls == 2 && !strcmp(backup.policydb.mls_seen, "s0:c5"));
    assert(reloaded.policydb.mls_calls == 1 && !strcmp(reloaded.policydb.mls_seen, "s0:c5"));
    assert(reloaded_sidtab.inserts == 1 && live_sidtab.inserts == 0);
    // the backup sidtab returns the same SID for the same context; the fake just counts
    assert(backup_sidtab.inserts == 2 && kmemdup_calls == 1);
}

T(other_live_sidtab_errors_are_not_retried)
{
    live_sidtab.fail_rc = -ENOMEM;
    struct result r = to_sid("u:r:untrusted_app:s0");
    assert(r.rc == 0 && r.orig_rc == -ENOMEM && r.orig_sid == SECSID_NULL);
    assert(rcu_locks == 1 && backup.policydb.mls_calls == 1);
}

T(backup_sidtab_failure_skips_live)
{
    backup_sidtab.fail_rc = -ENOMEM;
    struct result r = to_sid("u:r:untrusted_app:s0");
    assert(r.rc == -ENOMEM && r.orig_rc == -EINVAL && r.orig_sid == SECSID_NULL);
    assert(lookups(&live.policydb) == 0 && live_sidtab.inserts == 0);
}

T(selinuxfs_callers_without_orig_pointers_still_sync)
{
    // my_write_context / my_write_access pass NULL, NULL; stock grows the live sidtab there too
    char ctx[] = "u:r:app_zygote:s0";
    u32 sid = 0;
    int rc = security_context_to_sid_with_policy(&backup, ctx, strlen(ctx), &sid, SECSID_NULL, GFP_KERNEL, NULL, NULL);
    assert(rc == 0 && sid == 1002 && live_sidtab.inserts == 1 && rcu_depth == 0);
}

T(length_bounds_the_parse)
{
    // the caller's buffer need not end where the context does
    const char buf[] = "u:r:untrusted_app:s0:c9garbage";
    u32 sid = 0;
    int rc = security_context_to_sid_with_policy(&backup, buf, strlen("u:r:untrusted_app:s0"), &sid, SECSID_NULL,
                                                 GFP_KERNEL, NULL, NULL);
    assert(rc == 0 && !strcmp(backup.policydb.mls_seen, "s0") && !strcmp(live.policydb.mls_seen, "s0"));
}

T(empty_context_is_rejected_without_alloc)
{
    u32 sid = 5, orig_sid = 5;
    int orig_rc = 5;
    int rc = security_context_to_sid_with_policy(&backup, "", 0, &sid, SECSID_NULL, GFP_KERNEL, &orig_sid, &orig_rc);
    assert(rc == -EINVAL && kmemdup_calls == 0 && rcu_locks == 0);
}

int main(void)
{
    RUN(rejected_type_costs_one_parse_like_stock);
    RUN(rejected_user_stops_at_first_lookup);
    RUN(rejected_mls_never_reaches_live);
    RUN(attribute_is_not_a_type);
    RUN(live_only_context_stays_hidden);
    RUN(accepted_context_syncs_live_sidtab);
    RUN(context_without_mls_is_rejected);
    RUN(live_rejection_leaves_orig_sid_null);
    RUN(stale_live_sidtab_retries_on_reloaded_policy);
    RUN(other_live_sidtab_errors_are_not_retried);
    RUN(backup_sidtab_failure_skips_live);
    RUN(selinuxfs_callers_without_orig_pointers_still_sync);
    RUN(length_bounds_the_parse);
    RUN(empty_context_is_rejected_without_alloc);
    printf("%d passed\n", passed);
    return 0;
}
