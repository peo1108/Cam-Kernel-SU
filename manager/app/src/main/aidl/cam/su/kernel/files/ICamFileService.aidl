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
    // zip archives edited in place (rewritten through a temp file); tar is read-only
    void archiveRename(String archive, String from, String to);
    void archiveMkdir(String archive, String inner);
    // replaces (or adds) the file at inner with what source carries, read to EOF
    void archiveWrite(String archive, String inner, in ParcelFileDescriptor source);

    // pm install of an .apk or a split bundle; returns pm's answer ("Success" or the failure)
    String installPackage(String path);

    // MD5, SHA-1, SHA-256 (lowercase hex)
    String[] checksums(String path);
    // up to length bytes from offset (hex viewer)
    byte[] readRange(String path, long offset, int length);
    // name pattern (* ? wildcards) and optional content; stops at limit or cancelSearch()
    ParcelableListSlice<FileEntry> search(String root, String pattern, String content, int limit);
    void cancelSearch();
    // running app processes as "pid\tuid\tname" (uid >= 10000), for browsing as an app sees
    String[] appProcesses();

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
