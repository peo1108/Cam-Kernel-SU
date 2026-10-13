// IFileOpCallback.aidl
package cam.su.kernel.files;

// Events of one operation started with ICamFileService.startOp.
oneway interface IFileOpCallback {
    // bytes for copy/move, entries for delete; at most every 100 ms
    void onProgress(int id, long done, long total, String current);

    // the operation waits until resolveConflict(id, ...) or cancelOp(id)
    void onConflict(int id, String src, String dst, boolean srcIsDir, boolean dstIsDir);

    // errno 0: finished; -1: cancelled; otherwise the error, with error text
    void onDone(int id, int items, int skipped, int errno, String error);
}
