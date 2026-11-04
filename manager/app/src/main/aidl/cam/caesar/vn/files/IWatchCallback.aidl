// IWatchCallback.aidl
package cam.caesar.vn.files;

// A folder the Files tab shows changed (ICamFileService.watch).
oneway interface IWatchCallback {
    // something in dir was created, deleted, renamed, written or had its mode changed
    void onChanged(String dir);
}
