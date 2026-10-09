#ifndef __KSU_H_CAMD
#define __KSU_H_CAMD

#include <asm/syscall.h>

#define CAMD_PATH "/data/adb/camd"

void ksu_camd_init();
void ksu_camd_exit();

void ksu_execve_hook_camd(const struct pt_regs *regs);
void ksu_execveat_hook_camd(const struct pt_regs *regs);
void ksu_stop_input_hook_runtime(void);

#endif
