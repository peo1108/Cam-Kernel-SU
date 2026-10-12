import { defineConfig, type DefaultTheme } from 'vitepress'

function guideVi(): DefaultTheme.SidebarItem[] {
  return [
    {
      text: 'Bắt đầu',
      items: [
        { text: 'Giới thiệu', link: '/huong-dan/' },
        { text: 'Cài đặt', link: '/huong-dan/cai-dat' },
        { text: 'Cấp quyền root', link: '/huong-dan/cap-quyen' },
      ],
    },
    {
      text: 'Sử dụng',
      items: [
        { text: 'Module và metamodule', link: '/huong-dan/module' },
        { text: 'Tab Tính năng', link: '/huong-dan/tinh-nang' },
        { text: 'Kiểm tra ẩn root', link: '/huong-dan/an-root' },
        { text: 'Câu hỏi thường gặp', link: '/huong-dan/hoi-dap' },
      ],
    },
  ]
}

function guideEn(): DefaultTheme.SidebarItem[] {
  return [
    {
      text: 'Getting started',
      items: [
        { text: 'Introduction', link: '/en/guide/' },
        { text: 'Installation', link: '/en/guide/install' },
        { text: 'Granting root', link: '/en/guide/root-access' },
      ],
    },
    {
      text: 'Using it',
      items: [
        { text: 'Modules and metamodules', link: '/en/guide/modules' },
        { text: 'Features tab', link: '/en/guide/features' },
        { text: 'Root hiding check', link: '/en/guide/hiding' },
        { text: 'FAQ', link: '/en/guide/faq' },
      ],
    },
  ]
}

// VitePress's slugify keeps "đ" (NFKD does not split it), giving anchors like #cai-đat.
// Same rules as @mdit-vue/shared, plus đ -> d, so Vietnamese headings get plain ASCII ids.
function slugify(s: string): string {
  return s
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/đ/g, 'd')
    .replace(/Đ/g, 'D')
    .replace(/[\u0000-\u001f]/g, '')
    .replace(/[\s~`!@#$%^&*()\-_+=[\]{}|\\;:"'“”‘’<>,.?/]+/g, '-')
    .replace(/-{2,}/g, '-')
    .replace(/^-+|-+$/g, '')
    .replace(/^(\d)/, '_$1')
    .toLowerCase()
}

function guideZh(): DefaultTheme.SidebarItem[] {
  return [
    {
      text: '开始',
      items: [
        { text: '简介', link: '/zh/guide/' },
        { text: '安装', link: '/zh/guide/install' },
        { text: '授予 Root', link: '/zh/guide/root-access' },
      ],
    },
    {
      text: '使用',
      items: [
        { text: '模块与元模块', link: '/zh/guide/modules' },
        { text: '功能页', link: '/zh/guide/features' },
        { text: 'Root 隐藏检测', link: '/zh/guide/hiding' },
        { text: '常见问题', link: '/zh/guide/faq' },
      ],
    },
  ]
}

export default defineConfig({
  title: 'Cam Kernel SU',
  markdown: { anchor: { slugify } },
  cleanUrls: true,
  lastUpdated: true,
  sitemap: { hostname: 'https://caesarvn.xyz' },
  // Local preview/dev: send /api to `node site/api/server.mjs` (on the VPS Caddy does this).
  vite: {
    server: { proxy: { '/api': 'http://127.0.0.1:7792' } },
    preview: { proxy: { '/api': 'http://127.0.0.1:7792' } },
  },
  head: [
    // Set before first paint so entrance cards start hidden instead of flashing (theme/glass/runtime.ts).
    ['script', {}, "document.documentElement.classList.add('lg-js')"],
    ['link', { rel: 'icon', href: '/favicon.ico', sizes: '48x48' }],
    ['link', { rel: 'icon', href: '/favicon.png', type: 'image/png' }],
    ['link', { rel: 'apple-touch-icon', href: '/apple-touch-icon.png' }],
    ['meta', { name: 'theme-color', content: '#f6f7fb', media: '(prefers-color-scheme: light)' }],
    ['meta', { name: 'theme-color', content: '#000000', media: '(prefers-color-scheme: dark)' }],
    ['meta', { property: 'og:image', content: 'https://caesarvn.xyz/og.jpg' }],
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ['link', { rel: 'preconnect', href: 'https://fonts.googleapis.com' }],
    ['link', { rel: 'preconnect', href: 'https://fonts.gstatic.com', crossorigin: '' }],
    [
      'link',
      {
        rel: 'stylesheet',
        href: 'https://fonts.googleapis.com/css2?family=Baloo+2:wght@500;600;700;800&family=Be+Vietnam+Pro:wght@400;500;600&display=swap',
      },
    ],
  ],
  themeConfig: {
    logo: '/logo.png',
    search: {
      provider: 'local',
      options: {
        locales: {
          zh: {
            translations: {
              button: { buttonText: '搜索', buttonAriaLabel: '搜索' },
              modal: {
                noResultsText: '没有结果',
                resetButtonTitle: '清除',
                footer: { selectText: '选择', navigateText: '切换', closeText: '关闭' },
              },
            },
          },
          root: {
            translations: {
              button: { buttonText: 'Tìm kiếm', buttonAriaLabel: 'Tìm kiếm' },
              modal: {
                noResultsText: 'Không tìm thấy',
                resetButtonTitle: 'Xoá',
                footer: { selectText: 'chọn', navigateText: 'di chuyển', closeText: 'đóng' },
              },
            },
          },
        },
      },
    },
  },
  locales: {
    root: {
      label: 'Tiếng Việt',
      lang: 'vi-VN',
      description: 'Root Android từ bên trong nhân. Giao diện kính, chống bootloop, kiểm tra ẩn root.',
      themeConfig: {
        nav: [
          { text: 'Hướng dẫn', link: '/huong-dan/', activeMatch: '/huong-dan/' },
          { text: 'Module', link: '/module' },
          { text: 'Nhật ký', link: '/nhat-ky' },
          { text: 'Tải về', link: '/tai-ve' },
          { text: 'Mã nguồn', link: '/ma-nguon' },
          { text: 'Tài khoản', link: '/thanh-vien', activeMatch: '/(thanh-vien|tai-khoan|quan-tri)' },
        ],
        sidebar: { '/huong-dan/': guideVi() },
        outline: { label: 'Trên trang này', level: [2, 3] },
        docFooter: { prev: 'Trang trước', next: 'Trang sau' },
        lastUpdated: { text: 'Cập nhật lần cuối' },
        darkModeSwitchLabel: 'Giao diện',
        lightModeSwitchTitle: 'Chuyển sang sáng',
        darkModeSwitchTitle: 'Chuyển sang tối',
        sidebarMenuLabel: 'Mục lục',
        returnToTopLabel: 'Lên đầu trang',
        langMenuLabel: 'Ngôn ngữ',
        notFound: {
          title: 'KHÔNG TÌM THẤY TRANG',
          quote: 'Trang này không tồn tại hoặc đã bị chuyển đi.',
          linkText: 'Về trang chủ',
        },
        footer: {
          message: 'GPL · dựa trên KernelSU',
          copyright: 'Cam Kernel SU',
        },
      },
    },
    en: {
      label: 'English',
      lang: 'en-US',
      link: '/en/',
      description: 'Android root from inside the kernel. Glass interface, bootloop guard, root hiding check.',
      themeConfig: {
        nav: [
          { text: 'Guide', link: '/en/guide/', activeMatch: '/en/guide/' },
          { text: 'Modules', link: '/en/modules' },
          { text: 'Changelog', link: '/en/changelog' },
          { text: 'Download', link: '/en/download' },
          { text: 'Source', link: '/en/source' },
          { text: 'Account', link: '/en/member', activeMatch: '/en/(member|account)' },
        ],
        sidebar: { '/en/guide/': guideEn() },
        outline: { level: [2, 3] },
        footer: {
          message: 'GPL · built on KernelSU',
          copyright: 'Cam Kernel SU',
        },
      },
    },
    zh: {
      label: '简体中文',
      lang: 'zh-CN',
      link: '/zh/',
      description: '深藏内核的 Android Root。玻璃界面、防卡开机、Root 隐藏检测。',
      themeConfig: {
        nav: [
          { text: '指南', link: '/zh/guide/', activeMatch: '/zh/guide/' },
          { text: '模块', link: '/zh/modules' },
          { text: '更新日志', link: '/zh/changelog' },
          { text: '下载', link: '/zh/download' },
          { text: '源码', link: '/zh/source' },
          { text: '账号', link: '/zh/member', activeMatch: '/zh/(member|account)' },
        ],
        sidebar: { '/zh/guide/': guideZh() },
        outline: { label: '本页内容', level: [2, 3] },
        docFooter: { prev: '上一页', next: '下一页' },
        lastUpdated: { text: '最后更新' },
        darkModeSwitchLabel: '外观',
        lightModeSwitchTitle: '切换到浅色',
        darkModeSwitchTitle: '切换到深色',
        sidebarMenuLabel: '目录',
        returnToTopLabel: '回到顶部',
        langMenuLabel: '语言',
        notFound: { title: '页面不存在', quote: '这个页面不存在或已被移走。', linkText: '回到首页' },
        footer: {
          message: 'GPL · 基于 KernelSU',
          copyright: 'Cam Kernel SU',
        },
      },
    },
  },
})
