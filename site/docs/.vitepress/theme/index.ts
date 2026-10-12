import DefaultTheme from 'vitepress/theme'
import type { Theme } from 'vitepress'
import Layout from './Layout.vue'
import HomeGlass from './components/HomeGlass.vue'
import DownloadPanel from './components/DownloadPanel.vue'
import ChangelogList from './components/ChangelogList.vue'
import ModuleList from './components/ModuleList.vue'
import SourceRequest from './components/SourceRequest.vue'
import AccountPage from './components/AccountPage.vue'
import MemberPage from './components/MemberPage.vue'
import AdminPanel from './components/AdminPanel.vue'
import CheckoutPage from './components/CheckoutPage.vue'
import ConnectPage from './components/ConnectPage.vue'
import './style.css'

export default {
  extends: DefaultTheme,
  Layout,
  enhanceApp({ app, router }) {
    // Release files (/dl/) and feed files are not pages. VitePress routes any link whose extension it
    // does not know (.apk, .ko, camd-*) as a page and shows its 404 until a reload. Hand them to the
    // browser instead: the server answers with a download, or sends a visitor to sign in.
    const before = router.onBeforeRouteChange
    router.onBeforeRouteChange = async (to) => {
      if (/^\/(dl|feed)\//.test(new URL(to, location.origin).pathname)) {
        location.href = to
        return false
      }
      return before?.(to)
    }
    app.component('HomeGlass', HomeGlass)
    app.component('DownloadPanel', DownloadPanel)
    app.component('ChangelogList', ChangelogList)
    app.component('ModuleList', ModuleList)
    app.component('SourceRequest', SourceRequest)
    app.component('AccountPage', AccountPage)
    app.component('MemberPage', MemberPage)
    app.component('AdminPanel', AdminPanel)
    app.component('CheckoutPage', CheckoutPage)
    app.component('ConnectPage', ConnectPage)
  },
} satisfies Theme
