// IKsuInterface.aidl
package me.weishu.kernelsu;

import android.content.pm.PackageInfo;
import android.os.Bundle;
import rikka.parcelablelist.ParcelableListSlice;

// Served by KsuService (uid 0). Every privileged kernel call goes through here,
// because the kernel only accepts manager supercalls from uid 0.
interface IKsuInterface {
    ParcelableListSlice<PackageInfo> getPackages(int flags);

    int[] getUserIds();

    int getVersion();
    int getKernelUapiVersion();
    boolean isSafeMode();
    boolean isLkmMode();
    boolean isLkmBundled();
    boolean isLateLoadMode();
    boolean isPrBuild();

    boolean uidShouldUmount(int uid);

    // Bundle key "profile" holds a Natives.Profile
    Bundle getAppProfile(String key, int uid);
    boolean setAppProfile(in Bundle profile);

    boolean isSuEnabled();
    boolean setSuEnabled(boolean enabled);
    boolean isKernelUmountEnabled();
    boolean setKernelUmountEnabled(boolean enabled);
    boolean isSelinuxHideEnabled();
    int setSelinuxHideEnabled(boolean enabled);

    int getSuperuserCount();
}
