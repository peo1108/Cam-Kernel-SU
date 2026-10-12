// Browser side of the caesarvn.xyz API (site/api/server.mjs). Same origin, session cookie.

export type Lang = 'vi' | 'en' | 'zh'

export class ApiError extends Error {
  constructor(public status: number, public code: string) {
    super(code)
  }
}

export async function api<T = any>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    credentials: 'same-origin',
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  const data = text ? JSON.parse(text) : null
  if (!res.ok) throw new ApiError(res.status, data?.error ?? `http_${res.status}`)
  return data as T
}

const ERRORS: Record<string, Record<Lang, string>> = {
  bad_email: { vi: 'Email không hợp lệ.', en: 'That email does not look right.', zh: '邮箱格式不正确。' },
  bad_password: { vi: 'Mật khẩu từ 8 ký tự.', en: 'Passwords need 8 characters or more.', zh: '密码至少 8 个字符。' },
  email_taken: { vi: 'Email này đã có tài khoản. Đăng nhập nhé.', en: 'This email already has an account. Sign in instead.', zh: '该邮箱已注册，请直接登录。' },
  bad_login: { vi: 'Sai email hoặc mật khẩu.', en: 'Wrong email or password.', zh: '邮箱或密码错误。' },
  locked: { vi: 'Tài khoản đang bị khoá.', en: 'This account is locked.', zh: '账号已被锁定。' },
  not_yourself: { vi: 'Không làm việc này cho chính tài khoản đang dùng.', en: 'Not for your own account.', zh: '不能对自己的账号执行此操作。' },
  slow_down: { vi: 'Thử quá nhiều lần. Đợi một lát.', en: 'Too many tries. Wait a little.', zh: '尝试次数过多，请稍后再试。' },
  sign_in: { vi: 'Cần đăng nhập.', en: 'Please sign in.', zh: '请先登录。' },
  members_only: { vi: 'Chỉ dành cho thành viên.', en: 'Members only.', zh: '仅限会员。' },
  bad_token: { vi: 'Liên kết đã hết hạn.', en: 'This link has expired.', zh: '链接已过期。' },
  mail_off: { vi: 'Chưa bật gửi email. Liên hệ admin.', en: 'Email is not set up yet. Contact the admin.', zh: '邮件功能尚未开启，请联系管理员。' },
  google_off: { vi: 'Chưa bật đăng nhập Google.', en: 'Google sign-in is not set up yet.', zh: '尚未开启 Google 登录。' },
  invalid: { vi: 'Điền đủ các ô, tài khoản GitHub viết đúng.', en: 'Fill in every field, with a valid GitHub account.', zh: '请填写所有字段，并使用有效的 GitHub 账号。' },
  bad_totp: { vi: 'Mã xác thực sai hoặc đã dùng.', en: 'Wrong or already used code.', zh: '验证码错误或已使用。' },
  totp_required: { vi: 'Cần mã xác thực.', en: 'The code is required.', zh: '需要验证码。' },
  mfa_required: { vi: 'Cần nhập mã xác thực 2 bước.', en: 'Two-step code needed.', zh: '需要两步验证码。' },
  pay_off: { vi: 'Thanh toán chưa mở.', en: 'Payments are not open yet.', zh: '付款尚未开放。' },
  sales_closed: { vi: 'Tạm ngừng bán.', en: 'Sales are paused.', zh: '暂停销售。' },
  already_member: { vi: 'Bạn đã là thành viên.', en: 'You are already a member.', zh: '你已经是会员。' },
  already_open: { vi: 'Bản này đã mở cho bạn.', en: 'This release is already open for you.', zh: '该版本已对你开放。' },
  bad_code: { vi: 'Mã sai hoặc đã hết hạn.', en: 'Wrong or expired code.', zh: '代码错误或已过期。' },
  server: { vi: 'Máy chủ gặp lỗi. Thử lại sau.', en: 'Server error. Try again later.', zh: '服务器错误，请稍后再试。' },
}

export function errorText(e: unknown, lang: Lang): string {
  const code = e instanceof ApiError ? e.code : 'server'
  return ERRORS[code]?.[lang] ?? `${ERRORS.server[lang]} (${code})`
}

export const vnd = (n: number) => `${n.toLocaleString('vi-VN')}đ`

export function when(t: number | null | undefined, lang: Lang = 'vi'): string {
  if (!t) return '—'
  const d = new Date(t)
  const pad = (x: number) => String(x).padStart(2, '0')
  const date = lang === 'vi' ? `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()}` : `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  return `${date} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function day(t: number | null | undefined, lang: Lang = 'vi'): string {
  return when(t, lang).split(' ')[0]
}
