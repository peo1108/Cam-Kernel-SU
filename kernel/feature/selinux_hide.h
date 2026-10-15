#ifndef __KSU_H_SELINUX_HIDE
#define __KSU_H_SELINUX_HIDE

#include <linux/types.h>

// True while hiding is active; reset_avc_cache() drops the netlink policyload
// broadcast then, since stock never reloads policy after boot.
bool ksu_selinux_hide_is_running(void);

void ksu_selinux_hide_init();
void ksu_selinux_hide_exit();
void ksu_selinux_hide_drop_backup_if_unused();
void ksu_selinux_hide_handle_second_stage();
void ksu_selinux_hide_handle_post_fs_data();

#endif
