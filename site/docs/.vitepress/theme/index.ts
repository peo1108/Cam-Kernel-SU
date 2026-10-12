import DefaultTheme from 'vitepress/theme'
import type { Theme } from 'vitepress'
import Layout from './Layout.vue'
import HomeGlass from './components/HomeGlass.vue'
import DownloadPanel from './components/DownloadPanel.vue'
import ChangelogList from './components/ChangelogList.vue'
import ModuleList from './components/ModuleList.vue'
import SourceRequest from './components/SourceRequest.vue'
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
  },
} satisfies Theme
