# Renames KernelSU names to the Cam ones in code that came in from upstream.
# Run it ONLY on files a merge brought in or changed, never on the whole repo,
# and never on the compatibility files in scripts/cam_rename.skip: they keep
# the old names on purpose (docs/CAM_CHANGES.md, section 11).
#
#   git diff --name-only ORIG_HEAD HEAD -- manager/app/src userspace kernel .github scripts \
#     | grep -v -f scripts/cam_rename.skip | xargs -r sed -i -f scripts/cam_rename.sed

# Manager: package and classes
s#me/weishu/kernelsu#cam/su/kernel#g
s/me\.weishu\.kernelsu/cam.su.kernel/g
s/Java_me_weishu_kernelsu_/Java_cam_su_kernel_/g
s/\bKernelSUApplication\b/CamApplication/g
s/\bMiuixKernelSUTheme\b/MiuixCamTheme/g
s/\bKernelSUTheme\b/CamTheme/g
s/\brememberKernelSUColorScheme\b/rememberCamColorScheme/g
s/Theme\.KernelSU/Theme.Cam/g
s/\bKsuServiceClient\b/CamRootClient/g
s/\bKsuService\b/CamRootService/g
s/\bIKsuInterface\b/ICamRootService/g
s/\bKsuCliKt\b/CamCliKt/g
s/\bKsuCli\b/CamCli/g
s/\bKsuIsValid\b/CamIsValid/g
s/\bKsuValidCheck\b/CamValidCheck/g
s/\bKsuDeepLink\b/CamDeepLink/g
s/\bKsu\b/Cam/g
s/_Natives_/_CamNative_/g
s/\bNatives\b/CamNative/g
s/\bMainActivity/CamActivity/g
s/\bksu\(App\|Version\|InitDone\|Ready\|Kernel\|FileSize\)\b/cam\1/g
s/\bgetKsuDaemonPath\b/getCamDaemonPath/g
s/\bexecKsudFeatureSave\b/execCamdFeatureSave/g
s/\bforkDontCareAndExecKsud\b/forkDontCareAndExecCamd/g
s/\bexecKsud\b/execCamd/g
s/\bksudStdout\b/camdStdout/g
s/\bksudPath\b/camdPath/g
s/\bFLAG_KSU_NO_NEW_PRIVS\b/FLAG_CAM_NO_NEW_PRIVS/g
s/\bKERNEL_SU_DOMAIN = /CAM_DOMAIN = /g
s/CamNative\.KERNEL_SU_DOMAIN/CamNative.CAM_DOMAIN/g
s/System\.loadLibrary("kernelsu")/System.loadLibrary("camjni")/g
s/\bAppZygotePreload\b/CamZygotePreload/g
s/\bMagicaService\b/CamJailbreakService/g
s/\bBootCompletedReceiver\b/CamBootReceiver/g
s/cam\.su\.kernel\.magica/cam.su.kernel.jailbreak/g
s#cam/su/kernel/magica#cam/su/kernel/jailbreak#g
s/_kernel_magica_/_kernel_jailbreak_/g
s/_AppZygotePreload_/_CamZygotePreload_/g
s/ExecKsud\b/ExecCamd/g

# JNI library target (manager/app/src/main/cpp/CMakeLists.txt)
s/project("kernelsu")/project("camjni")/g
s/\(add_library\|target_include_directories\|target_link_libraries\)(kernelsu\b/\1(camjni/g

# Manager: log tags and file names
s/"KernelSUMagica"/"CamJailbreak"/g
s/"KernelSU_\(bugreport\|module_action_log\|install_log\)_/"Cam_\1_/g
s/Log\.\([iwed]\)("KernelSU"/Log.\1("Cam"/g
s/"KernelSU\/\${BuildConfig/"CamSU\/${BuildConfig/g
s/"kernelsu-tmp-lkm\.ko"/"cam-tmp-lkm.ko"/g
s/"KernelSU deep link"/"Cam deep link"/g
s/"KernelSU: \$camKernel"/"Cam: $camKernel"/g
s/LOG_TAG "KernelSU"/LOG_TAG "Cam"/g

# ksud -> camd, ksuinit -> caminit, paths
s/ksuinit/caminit/g
s/ksud/camd/g
s/KSUD/CAMD/g
s#/data/adb/ksu\b#/data/adb/cam#g
s#/metadata/watchdog/ksu/#/metadata/watchdog/cam/#g
s#/metadata/ksu/#/metadata/cam/#g
s/\.ksurc\b/.camrc/g
s/ksu_backup_/cam_backup_/g
s/libksucam/libcamd/g
s/\bksuMounts\b/camMounts/g
s/\baudit_ksu_mounts\b/audit_cam_mounts/g
s/--magica/--jailbreak/g
s/--post-magica/--post-jailbreak/g
s/\bpost_magica\b/post_jailbreak/g
s/\bmagica\b/jailbreak/g

# LKM file name
s/kernelsu-objs/camsu-objs/g
s/+= kernelsu\.o/+= camsu.o/g
s/kernelsu\.ko/camsu.ko/g
s/kernelsu-\${kmi}\.ko/camsu-${kmi}.ko/g
s/kernelsu-\*\.ko/camsu-*.ko/g
s/build-kernelsu-ko/build-camsu-ko/g
s/called kernelsu\./called camsu./g
s/kernelsu_patched_/camsu_patched_/g
s/kernelsu_restore_/camsu_restore_/g
