package cam.caesar.vn.ui.screen.about

import cam.caesar.vn.Hidden
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import cam.caesar.vn.BuildConfig
import cam.caesar.vn.R
import cam.caesar.vn.ui.navigation3.LocalNavigator
import cam.caesar.vn.ui.util.CamLinks

@Composable
fun AboutScreen() {
    val navigator = LocalNavigator.current
    val uriHandler = LocalUriHandler.current
    // Cam's own places first; the upstream links are credit, worded as such (the repository of
    // Cam is private, and "our channel" sent customers to the upstream's)
    val camLinks = stringResource(
        id = R.string.about_cam_links,
        "<b><a href=\"${CamLinks.SITE}\">caesarvn.xyz</a></b>",
        "<b><a href=\"${CamLinks.CONTACT}\">Telegram</a></b>"
    )
    val upstreamLinks = stringResource(
        id = R.string.about_upstream_links,
        "<b><a href=\"${Hidden.UPSTREAM_GITHUB}\">GitHub</a></b>",
        "<b><a href=\"${Hidden.UPSTREAM_TELEGRAM}\">Telegram</a></b>"
    )
    val htmlString = "$camLinks<br/>$upstreamLinks"
    val state = AboutUiState(
        title = stringResource(R.string.about),
        appName = stringResource(R.string.app_name),
        versionName = BuildConfig.VERSION_NAME,
        links = extractLinks(htmlString),
    )
    val actions = AboutScreenActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onOpenLink = uriHandler::openUri,
    )

    AboutScreenMiuix(state, actions)
}
