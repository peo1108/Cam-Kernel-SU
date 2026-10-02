#ifndef __KSU_H_MANAGER_IDENTITY
#define __KSU_H_MANAGER_IDENTITY

#include <linux/cred.h>
#include <linux/types.h>

#define KSU_INVALID_APPID -1

// There is no manager app identity in the kernel: the manager is any
// root-granted app acting through a uid 0 process.
static inline bool is_manager(void)
{
    return current_uid().val == 0;
}

#endif // __KSU_H_MANAGER_IDENTITY
