// IScriptCallback.aidl
package cam.su.kernel.files;

// Output of one script started with ICamFileService.runScript.
oneway interface IScriptCallback {
    // stdout and stderr, in the order they came
    void onOutput(int id, String text);

    // the script ended: its exit code (128 + signal when it was killed)
    void onExit(int id, int code);
}
