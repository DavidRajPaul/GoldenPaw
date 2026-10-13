# GoldenPaw motion system

The **single source of truth** for transitions is
`composeApp/src/commonMain/kotlin/com/goldenpaw/ui/designsystem/MotionSpecs.kt`. Its KDoc holds the
token table; this page is the short guide.

## Layers

| Layer | File | Holds |
|---|---|---|
| Primitives | `ui/designsystem/Motion.kt` | M3 easing curves, duration tokens (SHORT2…EXTRA_LONG2), spring tokens; `pressScale`, `staggerIn`, `shimmer`, `AnimatedCounter` |
| Policy | `ui/designsystem/ReducedMotion.kt` | reduced-motion rules: the 120 ms fade, size snapping, `motionAwareAnimateItem`, `rememberAmbientTransition` |
| Tokens | `ui/designsystem/MotionSpecs.kt` | every enter/exit transition and content swap screens use |
| Bespoke | `Choreography.kt`, `Charts.kt`, `Celebration.kt`, `Components.kt` (rings, empty state) | one-off choreography; follows the same reduced-motion rules |

## Tokens

| Purpose | Token | Presets |
|---|---|---|
| Quick interaction feedback | `Modifier.pressScale` (Motion.kt), `MotionSpecs.tabIconPop` | – |
| Content fade-through | `MotionSpecs.fadeThrough` | `Quick` 150/150, `Stage` 220/120, `Screen` 400/200 |
| Spatial navigation | `MotionSpecs.axisX`, `predictivePop` | `NavPush`, `NavPop`, `NavTab`, `Step`, `InScreenTab`, `ScanStep` |
| Values and labels | `MotionSpecs.roll` | `Counter`, `Label` |
| Expand / collapse | `expandTransition` / `collapseTransition` (`MotionSpecs.expand`/`collapse`) | `Inline`, `Section`, `Field` |
| Overlays and chrome | `overlayEnter/Exit`, `scrimEnter/Exit`, `popoverEnter`, `splashExit`, `barEnter/Exit` | – |
| Modal sheets and dialogs | Material 3 built-in (no override exposed in this Compose Multiplatform version) | – |
| Success feedback | `MotionSpecs.successPop`, `contentDefault` | `Check`, `Chip` |
| Error feedback | `Modifier.errorShake(trigger)` | – |
| Lists | `motionAwareAnimateItem()` | – |
| Decorative loops | `rememberAmbientTransition(label)` | – |

## Rules

1. Screens don't call `slideIn…`, `scaleIn`, `expand…` or `fadeIn(tween(…))` directly. Pick a token.
2. Every `AnimatedVisibility` and `AnimatedContent` passes an explicit token.
3. Reduced motion: no translation, scale or size animation; a 120 ms fade at most; no infinite transitions.
4. Read animated values in `graphicsLayer` or draw lambdas, not in composition.
5. Need something new? Add a token or a preset here, with a test, rather than inline numbers.
