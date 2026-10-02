#ifndef __KSU_H_PKG_TRACKER
#define __KSU_H_PKG_TRACKER

// Re-read /data/system/packages.list, apply the patch-time root seed
// and prune allowlist entries whose package is gone.
void ksu_pkg_tracker_update(void);

#endif
