# LunoMusic -> SparkyTube port, step 1

Only YouTube-client things were taken from LunoMusic. No music features.

## What changed
| Area | Before | Now |
|---|---|---|
| Login | none; the feed silently read WebView cookies | `auth/LoginActivity` (Google sign-in WebView, like Luno's LoginScreen) + `auth/Session` (cookie, visitorData, datasync id, client version) |
| Feed client | `homefeed/InnerTubeClient`: no visitorData, no account context, errors swallowed, duration parsing stubbed, hard-coded `gl=US` | Luno-style headers, visitorData, `onBehalfOfUser`, real error messages + logs, duration from overlays, new `lockupViewModel` cards, filter chips, account info, locale-based `gl` |
| Feed UI | RecyclerView + XML item | Compose `ui/HomeFeed.kt` (account header, chips, pull to refresh, infinite scroll, rounded cards, sign-in card) in a `ComposeView` |
| Theme | XML only | `ui/SparkyTheme.kt` + `ui/at/Type.kt` ported from Luno (expressive motion, Poppins), keeping Sparky's red palette, light/dark, opt-in dynamic color |
| Logged out | toast + fall back to WebView feed | generic feed still loads, with a "Sign in" card |
| Build | AGP 8.5 / Kotlin 2.1 / compileSdk 34 | AGP 8.13 / Kotlin 2.2.20 / compileSdk 36 / Compose BOM 2025.10.01 / Gradle 8.13 (same as Luno) |

Native Home feed is now on by default (Settings > Native Home Feed still toggles it).

## Not verified
Written without an Android SDK, so it has NOT been compiled or run. First thing to do: push and let the GitHub Action build it.
Then check Settings > Logs for lines tagged `InnerTube`, `Login`, `Home`.

## Licensing
LunoMusic is GPL-3.0 (and its theme/typography come from ArchiveTune, GPL-3.0, (c) Rukamori; headers kept).
SparkyTube is MIT. Code copied from Luno makes the combined app GPL-3.0.
