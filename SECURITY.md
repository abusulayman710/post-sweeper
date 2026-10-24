# Security & Privacy Policy

### Privacy First Architecture

PostSweeper is designed from the ground up with zero telemetry, zero analytics, and zero intermediate servers:

1. **Direct Communication**: All network requests originate directly from the user's Android device to `x.com` and `api.x.com`. There is no proxy, relay, or third-party server.
2. **Credential Isolation**: When logging in, authentication occurs directly on `x.com` inside Android's system `WebView`. The application never requests, reads, intercepts, or stores your account password.
3. **Session Cookies**: Session cookies and CSRF tokens remain in Android's isolated `WebViewCookieManager` and local private app storage (`Room` and `EncryptedDataStore`). They are never exported or transmitted to any third party.
4. **Data Ownership**: Post caches, logs, and sweep statistics remain strictly on your device. Clearing app storage or logging out completely wipes local sessions.

### Reporting a Vulnerability

If you discover a security vulnerability or credential leak within PostSweeper, please report it responsibly:

- Do **not** open a public issue.
- Contact the project maintainers directly via GitHub Private Vulnerability Reporting or via email.
- Provide clear steps to reproduce the issue along with affected Android versions.

We appreciate responsible disclosure and will investigate reports promptly.
