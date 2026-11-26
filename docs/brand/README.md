# Nihongo Flow brand

**Mark: Ensō path.** An open circle that never quite closes, with a small dot for today: learning as a loop you return to, at your own pace.

| Use | Colour |
| --- | --- |
| Paper (background) | `#FAF7F2` |
| Pine (arc, light) | `#2F5D4A` |
| Pine light (dot light, arc dark) | `#8DBBA5` |
| Dot (dark) | `#4E7264` |
| Night (dark background) | `#161714` |

Colours match `ui/theme/Theme.kt`.

## Files

- `logo-mark.svg`: the mark alone.
- `logo-lockup-light.svg`, `logo-lockup-dark.svg`: mark, wordmark and tagline. The text is outlined, so no font is needed to render them.
- `icon-512.png`: Play Store listing icon.
- Android: `mipmap-anydpi-v26/ic_launcher*.xml` (adaptive icon with a monochrome layer for themed icons), `drawable/logo_mark.xml` (in-app mark that follows day/night via `brand_colors.xml`).

The wordmark is set in Zen Maru Gothic (SIL Open Font License). Keep the mark inside the 66dp safe zone of the 108dp adaptive icon canvas.
