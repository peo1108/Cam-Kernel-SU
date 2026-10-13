// ICamFileService.aidl
package cam.su.kernel.files;

import cam.su.kernel.files.FileEntry;
import cam.su.kernel.files.IFileOpCallback;
import rikka.parcelablelist.ParcelableListSlice;

// Served by CamFileService (uid 0) for the Files tab. Paths are absolute.
// Failures throw IllegalStateException carrying the errno (FsException.toRemote).
interface ICamFileService {
    ParcelableListSlice<FileEntry> listDir(String path);

    // null when nothing is there
    FileEntry stat(String path);

    // children of inner ("" = root) inside the archive at archive, as Archives.list
    ParcelableListSlice<FileEntry> listArchive(String archive, String inner);
    // one file inside an archive, as openRead
    ParcelFileDescriptor openArchiveEntry(String archive, String inner);
    // pm install of an .apk or a split bundle; returns pm's answer ("Success" or the failure)
    String installPackage(String path);

    // [total, available] bytes of the file system holding path
    long[] statFs(String path);
    // [bytes, files, folders] under path, not crossing symlinks
    long[] measure(String path);

    void mkdir(String path);
    void createFile(String path);
    void symlink(String target, String link);
    // never overwrites
    void rename(String from, String to);

    // recursive walks never follow symlinks
    void chmod(String path, int mode, boolean recursive);
    void chown(String path, int uid, int gid, boolean recursive);
    void setContext(String path, String context, boolean recursive);

    // read end of a reliable pipe; checkError() after EOF reports a failed read
    ParcelFileDescriptor openRead(String path);

    // reads source (a reliable pipe) to EOF, then replaces the file atomically,
    // keeping its mode, owner and SELinux label
    void writeFile(String path, in ParcelFileDescriptor source);

    // kind: OpKind ordinal (COPY, MOVE, DELETE, EXTRACT, COMPRESS); destDir null for DELETE.
    // Runs in the background; returns the id that callback events carry.
    int startOp(int kind, in String[] sources, String destDir, boolean preserve, IFileOpCallback callback);
    void cancelOp(int id);
    // choice: ConflictChoice ordinal (OVERWRITE, SKIP, RENAME)
    void resolveConflict(int id, int choice, boolean applyAll);
}
