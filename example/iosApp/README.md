# Kiche iOS example

SwiftUI host for the shared Compose UI (`:example:shared`), demonstrating the Kiche HTTP/3 client
on iOS. It renders `MainViewControllerKt.MainViewController()` (a `ComposeUIViewController`) inside
a SwiftUI `UIViewControllerRepresentable`.

## The Xcode project is generated

`iosApp.xcodeproj` is **not committed** — it's generated from [`project.yml`](project.yml) by
[XcodeGen](https://github.com/yonaskolb/XcodeGen), keeping the project config small and diffable
instead of a large hand-maintained `pbxproj`.

## Build & run

```
brew install xcodegen          # once
cd example/iosApp
xcodegen generate              # creates iosApp.xcodeproj
open iosApp.xcodeproj
```

In Xcode, select an iOS Simulator (or a device) and Run.

## How the shared framework is wired

`project.yml` adds a pre-build script that runs
`./gradlew :example:shared:embedAndSignAppleFrameworkForXcode`, which builds the static
`ComposeApp` framework and embeds it into the app. Swift finds it via `FRAMEWORK_SEARCH_PATHS`
(`…/shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)`) and links it through autolinking
(`import ComposeApp`) — no explicit `-framework` flag needed.

The first build also compiles quiche for iOS (BoringSSL) via the `buildQuicheApple` Gradle task —
slow the first time, cached afterwards. `libquiche.a` is statically linked into the framework via
the cinterop `staticLibraries` in `kiche/cinterop/quiche.def`.

## Notes

- TLS verification is disabled in the demo (`verifyPeer = false`) — see the parent
  [`README.md`](../README.md). Never ship that in a real app.
- iOS networking uses Kiche's raw UDP sockets (ktor-network), not `URLSession`, so App Transport
  Security does not apply. Pointing the demo at `localhost` instead of a remote host would
  additionally require `NSLocalNetworkUsageDescription` in `Info.plist`.
