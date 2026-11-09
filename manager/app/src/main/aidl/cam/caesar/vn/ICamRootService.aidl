// ICamRootService.aidl
package cam.caesar.vn;

import android.content.pm.PackageInfo;
import android.os.Bundle;
import rikka.parcelablelist.ParcelableListSlice;

// Served by CamRootService (uid 0). Every privileged kernel call goes through here,
// because the kernel only accepts manager supercalls from uid 0.
interface ICamRootService {
    ParcelableListSlice<PackageInfo> getPackages(int flags);

    int[] getUserIds();

    int getVersion();
    int getKernelUapiVersion();
    boolean isSafeMode();
    boolean isLkmMode();
    boolean isLkmBundled();
    boolean isLateLoadMode();
    boolean isPrBuild();
    String getSourceId();

    boolean uidShouldUmount(int uid);

    // Bundle key "profile" holds a CamNative.Profile
    Bundle getAppProfile(String key, int uid);
    boolean setAppProfile(in Bundle profile);

    boolean isSuEnabled();
    boolean setSuEnabled(boolean enabled);
    boolean isKernelUmountEnabled();
    boolean setKernelUmountEnabled(boolean enabled);
    boolean isSelinuxHideEnabled();
    int setSelinuxHideEnabled(boolean enabled);

    int getSuperuserCount();

    // keystore2 attestation (tier 2): the Manager is the brain; these run as root.
    boolean writeAttestConfig(String bootJson, String pushJson);
    boolean setAttestEnabled(boolean enabled);
    String attestStatus();
    boolean deployTeesim();
    int purgeTargetAttestKeys(in int[] uids);
    // JSON {publicKeyDigest, digest} of the real ro.boot.vbmeta.* values (hex), read as root, for the
    // harvest to stand in when the TEE reported an all-zero verified-boot key/hash (unlocked device).
    String vbmetaBootProps();
}
