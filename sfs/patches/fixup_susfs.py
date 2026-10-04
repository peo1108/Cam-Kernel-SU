#!/usr/bin/env python3
"""Adjust susfs4ksu's GKI patch to the GKI release a target builds.

Usage: fixup_susfs.py <fixup set> <50_add_susfs_in_gki patch>

The patch is applied with --fuzz=0: fuzzy matching once dropped a SUS_MAP hunk
into the middle of a function call in fs/proc/task_mmu.c. Where a release's
code around a hunk differs from what the patch expects, the hunk's context
lines are rewritten here instead; the lines SUSFS adds never change. A fragment
that adds a context line also fixes its hunk's @@ line counts.

Each target in sfs/targets.json names a fixup set; a set lists the fragments to
apply in order. Every fragment must match exactly once, or the script fails.
"""

import sys

# name -> (old, new)
FRAGMENTS = {
    # fs/exec.c, 6.1 and 6.6 before linux/dma-buf.h was included there
    "exec-no-dma-buf": (
        "+#include <linux/susfs_def.h>\n"
        "+#endif\n"
        " \n"
        " #ifndef __GENKSYMS__\n"
        " #include <linux/dma-buf.h>\n",
        "+#include <linux/susfs_def.h>\n"
        "+#endif\n"
        " \n"
        " #include <linux/uaccess.h>\n"
        " #include <asm/mmu_context.h>\n",
    ),
    # fs/proc/base.c, 6.1 before linux/dma-buf.h was included there
    "6.1-base-no-dma-buf": (
        " #include <linux/cn_proc.h>\n"
        " #include <linux/cpufreq_times.h>\n"
        " #include <linux/dma-buf.h>\n"
        "+#if defined(CONFIG_KSU_SUSFS_SUS_MAP) || defined(CONFIG_KSU_SUSFS_OPEN_REDIRECT)\n",
        " #include <linux/resctrl.h>\n"
        " #include <linux/cn_proc.h>\n"
        " #include <linux/cpufreq_times.h>\n"
        "+#if defined(CONFIG_KSU_SUSFS_SUS_MAP) || defined(CONFIG_KSU_SUSFS_OPEN_REDIRECT)\n",
    ),
    # fs/proc/base.c, 6.6 before linux/dma-buf.h was included there
    "6.6-base-no-dma-buf": (
        " #include <linux/ksm.h>\n"
        " #include <linux/cpufreq_times.h>\n"
        " #include <linux/dma-buf.h>\n"
        "+#if defined(CONFIG_KSU_SUSFS_SUS_MAP) || defined(CONFIG_KSU_SUSFS_OPEN_REDIRECT)\n",
        " #include <linux/cn_proc.h>\n"
        " #include <linux/ksm.h>\n"
        " #include <linux/cpufreq_times.h>\n"
        "+#if defined(CONFIG_KSU_SUSFS_SUS_MAP) || defined(CONFIG_KSU_SUSFS_OPEN_REDIRECT)\n",
    ),
    # fs/proc/task_mmu.c, 6.1 and 6.6 while show_smap() split pad and data vmas
    "show-smap-data-vma": (
        " \tstruct vm_area_struct *vma = v;\n"
        " \tstruct mem_size_stats mss;\n"
        " \n"
        "+#ifdef CONFIG_KSU_SUSFS_SUS_MAP\n",
        " \tstruct vm_area_struct *vma = get_data_vma(v);\n"
        " \tstruct mem_size_stats mss;\n"
        " \n"
        "+#ifdef CONFIG_KSU_SUSFS_SUS_MAP\n",
    ),
    # fs/namespace.c, 6.1 once trace/hooks/blk.h follows internal.h: one more context line
    "6.1-namespace-blk-hooks": (
        "@@ -32,10 +32,20 @@\n"
        " #include <linux/fs_context.h>\n",
        "@@ -32,11 +32,21 @@\n"
        " #include <linux/fs_context.h>\n",
    ),
    "6.1-namespace-blk-hooks-line": (
        "+#endif // #ifdef CONFIG_KSU_SUSFS\n"
        " \n"
        " #include \"pnode.h\"\n"
        " #include \"internal.h\"\n"
        " \n"
        "+#ifdef CONFIG_KSU_SUSFS_SUS_MOUNT\n",
        "+#endif // #ifdef CONFIG_KSU_SUSFS\n"
        " \n"
        " #include \"pnode.h\"\n"
        " #include \"internal.h\"\n"
        " #include <trace/hooks/blk.h>\n"
        " \n"
        "+#ifdef CONFIG_KSU_SUSFS_SUS_MOUNT\n",
    ),
    # security/selinux/hooks.c, 6.12 generation 5: no ANDROID policycap comment yet
    "6.12-hooks-no-policycap-comment": (
        "+#endif // #ifdef CONFIG_KSU_SUSFS\n"
        " \n"
        " /*\n"
        "  * ANDROID: selinux_state is part of the KMI, and adding memfd_class as part of the policycap\n",
        "+#endif // #ifdef CONFIG_KSU_SUSFS\n"
        " \n"
        " /* SECMARK reference count */\n"
        " static atomic_t selinux_secmark_refcount = ATOMIC_INIT(0);\n",
    ),
    # fs/exec.c, 6.12 generation 6: linux/dma-buf.h sits after linux/ksm.h
    "6.12-exec-dma-buf": (
        " #include <linux/user_events.h>\n"
        " #include <linux/rseq.h>\n"
        " #include <linux/ksm.h>\n"
        "+#ifdef CONFIG_KSU_SUSFS\n",
        " #include <linux/rseq.h>\n"
        " #include <linux/ksm.h>\n"
        " #include <linux/dma-buf.h>\n"
        "+#ifdef CONFIG_KSU_SUSFS\n",
    ),
    # fs/proc/base.c, 6.12 generation 6: linux/dma-buf.h sits after uapi/linux/lsm.h
    "6.12-base-dma-buf": (
        "+\n"
        " #include <uapi/linux/lsm.h>\n"
        " #include <trace/events/oom.h>\n"
        " #include <trace/hooks/sched.h>\n",
        "+\n"
        " #include <uapi/linux/lsm.h>\n"
        " #include <linux/dma-buf.h>\n"
        " #include <trace/events/oom.h>\n",
    ),
    # security/selinux/hooks.c, 6.12 from 2026-03: reworded ANDROID comment
    "6.12-hooks-backport-comment": (
        " * ANDROID: selinux_state is part of the KMI, and adding memfd_class as part of the policycap\n",
        " * ANDROID: selinux_state is part of the KMI, and backporting capabilities into\n",
    ),
    # fs/proc/task_mmu.c, 6.12 from 2026-03: show_smap() checks vma_data_pages()
    "6.12-show-smap-data-pages": (
        " \tif (!vma_pages(vma))\n"
        " \t\tgoto show_pad;\n",
        " \tif (!vma_data_pages(vma))\n"
        " \t\tgoto show_pad;\n",
    ),
}

# set name -> fragments, as referenced by sfs/targets.json ("none" = the patch as is)
SETS = {
    "none": [],
    "6.1-2025-03": ["exec-no-dma-buf", "6.1-base-no-dma-buf", "show-smap-data-vma"],
    "6.1-2025-07": ["exec-no-dma-buf", "6.1-base-no-dma-buf"],
    "6.1-2025-12": ["6.1-namespace-blk-hooks", "6.1-namespace-blk-hooks-line"],
    "6.6-2025-03": ["exec-no-dma-buf", "6.6-base-no-dma-buf", "show-smap-data-vma"],
    "6.6-2025-05": ["exec-no-dma-buf", "6.6-base-no-dma-buf"],
    "6.12-gen5": ["6.12-hooks-no-policycap-comment"],
    "6.12-2025-12": ["6.12-exec-dma-buf", "6.12-base-dma-buf"],
    "6.12-2026": [
        "6.12-exec-dma-buf",
        "6.12-base-dma-buf",
        "6.12-hooks-backport-comment",
        "6.12-show-smap-data-pages",
    ],
}


def main() -> int:
    name, path = sys.argv[1], sys.argv[2]
    if name not in SETS:
        print(f"unknown fixup set {name}", file=sys.stderr)
        return 1
    with open(path, encoding="utf-8") as f:
        text = f.read()
    for fragment in SETS[name]:
        old, new = FRAGMENTS[fragment]
        count = text.count(old)
        if count != 1:
            print(f"fixup {fragment} ({name}) matched {count} times:\n{old}", file=sys.stderr)
            return 1
        text = text.replace(old, new)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print(f"applied fixup set {name}: {', '.join(SETS[name]) or 'nothing'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
