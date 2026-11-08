package cam.caesar.vn.attest

import cam.caesar.vn.Cam
import org.json.JSONObject

/**
 * UI-facing orchestration of the attestation feature over the [Cam] root service. Harvest, resolve
 * and keybox parsing happen Manager-side (off this object); this wraps the root-side config write,
 * interceptor deploy, and flag toggle. The daemon activates on the next boot (camd `on_services`).
 */
object AttestRepository {

    data class Status(
        val enabled: Boolean,
        val libDeployed: Boolean,
        val injectDeployed: Boolean,
        val pushConfigured: Boolean,
    ) {
        val deployed: Boolean get() = libDeployed && injectDeployed
    }

    fun status(): Status = JSONObject(Cam.attestStatus()).let {
        Status(
            enabled = it.optBoolean("enabled"),
            libDeployed = it.optBoolean("libDeployed"),
            injectDeployed = it.optBoolean("injectDeployed"),
            pushConfigured = it.optBoolean("pushConfigured"),
        )
    }

    /**
     * Write the resolved config (boot.json + push.json) and deploy the interceptor. Does NOT flip the
     * flag — call [enable] after the user confirms. Returns false if the write or deploy failed.
     */
    fun configure(
        harvest: HarvestRecord,
        profiles: List<AttestProfile>,
        uidsOf: (String) -> List<Pair<Int, Int>>,
    ): Boolean {
        val push = resolvePush(profiles, harvest, uidsOf)
        if (!Cam.writeAttestConfig(harvest.toJson(), push)) return false
        return Cam.deployTeesim()
    }

    fun enable(on: Boolean): Boolean = Cam.setAttestEnabled(on)
}
