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
  enhanceApp({ app }) {
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
