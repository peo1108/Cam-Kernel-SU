// IHidingProbe.aidl
package cam.su.kernel.hiding;

// Served by HidingProbeService in an isolated process: no root, modules unmounted,
// the same view a banking app or a game gets.
interface IHidingProbe {
    // HidingAudit JSON, the same shape `camd hiding-audit` prints
    String scan();
}
