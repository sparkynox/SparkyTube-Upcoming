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


## Step 2: Compose owns the whole YouTube side

| Problem | Cause | Fix |
|---|---|---|
| App opened on the WebView, Compose only after tapping Home | Compose feed was only shown from the Home tab click | Compose UI is shown from the first frame (`showComposeHost()` in onCreate) |
| Video screen was the WebView watch page | Player was drawn over a WebView page | `ui/WatchScreen.kt`: title, channel, description, up next, all from InnerTube (`next`) |
| Home / back did nothing while a video played | Home tap showed the feed but left the player on top; back walked the hidden WebView's history; a 600ms poller stopped playback whenever the WebView URL was not /watch | One `NavViewModel` (tab, pages, watch). Back = collapse to mini-player, then pop pages, then Home, then exit. URL poller is off in Compose mode |
| 3-finger screenshot started a video | The swipe landed on a video card and counted as a tap | `dispatchTouchEvent` sends CANCEL when a 3rd finger goes down and swallows the rest of that gesture |
| Subs / Library / Shorts / search were WebView | WebView navigation | Compose screens over InnerTube: `FEsubscriptions`, `FEhistory`, `VLLL`, `VLWL`, `FEplaylist_aggregation`, `search`; Shorts harvested from the home feed |
| Bottom bar | XML | `AppNavBar` (Material 3 NavigationBar) |

Autoplay-next now uses the first related video from the Watch screen instead of clicking inside the WebView page.

Still WebView: YouTube settings page, Anime (Crunchyroll) mode. Still XML: top bar, mini-player frame, player controls.
Not done yet: comments, Shorts as a vertical swipe pager, like/subscribe buttons.
