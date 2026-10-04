#!/usr/bin/env python3
"""Adjust susfs4ksu's GKI patch to the GKI release branch this project pins.

Usage: fixup_susfs.py <kmi> <50_add_susfs_in_gki patch>

CI applies the patch with --fuzz=0: fuzzy matching once dropped a SUS_MAP hunk
into the middle of a function call in fs/proc/task_mmu.c. When the pinned
branch's context differs from what the patch expects, the context lines are
rewritten here instead. Only context (' ') lines change, so hunk sizes stay
valid. Every fixup must match exactly once, or the script fails.
"""

import sys

# kmi -> list of (old, new) context blocks
FIXUPS = {
    # common-android16-6.12-2026-06
    "android16-6.12": [
        # fs/exec.c: linux/dma-buf.h sits after linux/ksm.h
        (
            " #include <linux/user_events.h>\n"
            " #include <linux/rseq.h>\n"
            " #include <linux/ksm.h>\n"
            "+#ifdef CONFIG_KSU_SUSFS\n",
            " #include <linux/rseq.h>\n"
            " #include <linux/ksm.h>\n"
            " #include <linux/dma-buf.h>\n"
            "+#ifdef CONFIG_KSU_SUSFS\n",
        ),
        # fs/proc/base.c: linux/dma-buf.h sits after uapi/linux/lsm.h
        (
            "+\n"
            " #include <uapi/linux/lsm.h>\n"
            " #include <trace/events/oom.h>\n"
            " #include <trace/hooks/sched.h>\n",
            "+\n"
            " #include <uapi/linux/lsm.h>\n"
            " #include <linux/dma-buf.h>\n"
            " #include <trace/events/oom.h>\n",
        ),
        # security/selinux/hooks.c: reworded ANDROID comment
        (
            " * ANDROID: selinux_state is part of the KMI, and adding memfd_class as part of the policycap\n",
            " * ANDROID: selinux_state is part of the KMI, and backporting capabilities into\n",
        ),
        # fs/proc/task_mmu.c: show_smap() checks vma_data_pages()
        (
            " \tif (!vma_pages(vma))\n"
            " \t\tgoto show_pad;\n",
            " \tif (!vma_data_pages(vma))\n"
            " \t\tgoto show_pad;\n",
        ),
    ],
}


def main() -> int:
    kmi, path = sys.argv[1], sys.argv[2]
    with open(path, encoding="utf-8") as f:
        text = f.read()
    for old, new in FIXUPS.get(kmi, []):
        count = text.count(old)
        if count != 1:
            print(f"fixup for {kmi} matched {count} times:\n{old}", file=sys.stderr)
            return 1
        text = text.replace(old, new)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print(f"applied {len(FIXUPS.get(kmi, []))} fixups for {kmi}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
