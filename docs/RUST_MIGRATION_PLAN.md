# SkyTerm: Rust and Android migration plan

## Goal

Move performance-sensitive and platform-independent core logic toward Rust while preserving the Termux shell and package ecosystem. Migrate in small layers so each stage can keep the existing Java implementation available until the replacement is ready.

SkyTerm keeps the `com.termux` application ID and Termux `$PREFIX` so the existing bootstrap binaries remain valid. Its launcher label is `SkyTerm`. Normal terminal sessions now start Ubuntu through PRoot-Distro. On first use, the app installs PRoot-Distro and its Ubuntu userland automatically; this needs an internet connection and storage, while Termux remains available as a fallback shell and package-management layer.

## Non-negotiable compatibility principle

Rust is an implementation choice for improving SkyTerm's internal performance. It is not a reason to replace or reshape the Unix environment users and packages already depend on. Keep APT/dpkg, Bash and other packaged shells, PTY behavior, the Termux `$PREFIX` and filesystem layout, and the existing package/bootstrap formats compatible. Put Rust changes behind the current interfaces, and adopt an optimization only after its external behavior matches the existing implementation and measurements show a benefit.

In practice, changes must preserve package installation and maintainer scripts, shell startup and commands, paths and environment variables, PTY input/output and resize behavior, process signals and exit status, and user configuration files. If a Rust implementation cannot preserve those contracts, retain the current implementation as the active path and continue the migration in a smaller layer.

## Capability areas

This table describes the target capability map. It is an architectural scope, not a claim that every capability is already implemented.

| No. | Capability area | Main function |
| --- | --- | --- |
| 1 | Application Core Engine | Main application engine, with performance-sensitive internals written in Rust. |
| 2 | Android Integration | Integrate the application with Android through its supported APIs and lifecycle. |
| 3 | Terminal Engine | Process terminal input and output and render terminal content. |
| 4 | Unix Compatibility | Preserve compatibility with Unix, POSIX, and Linux tools and behavior where Android permits. |
| 5 | Process Management | Manage and supervise application-managed processes. |
| 6 | PTY Management | Manage pseudo-terminals while preserving existing session semantics. |
| 7 | Session Management | Create and manage multiple terminal sessions. |
| 8 | Session Recovery | Restore available session and work context after interruption. |
| 9 | Resource Management | Monitor and manage CPU, memory, and storage use within Android limits. |
| 10 | Package Management | Install and update packages through the existing APT/dpkg ecosystem. |
| 11 | Package Security | Verify package integrity and provide package security auditing. |
| 12 | Environment Management | Create environment snapshots and support rollback where the underlying tools allow it. |
| 13 | Linux Distribution Management | Manage Ubuntu, Debian, and Alpine user-space environments. |
| 14 | Filesystem Management | Manage filesystems and permissions while preserving Termux paths and layout. |
| 15 | Network Management | Provide network diagnostics and manage app-level network settings. |
| 16 | Android Capability Management | Integrate camera, sensors, clipboard, and Android APIs subject to Android permissions. |
| 17 | Development Environment | Provide development environments for multiple programming languages through packages and toolchains. |
| 18 | Build System | Compile the application and support reproducible builds. |
| 19 | Security Management | Provide isolation, access controls, and application security features within Android's model. |
| 20 | Extension Management | Manage third-party plugins and extensions with explicit trust and permission boundaries. |
| 21 | Logging & Diagnostics | Record relevant activity and help diagnose problems. |
| 22 | Compatibility Testing | Check compatibility across supported devices, Android versions, and CPU architectures. |
| 23 | Storage & Backup | Manage application data and support backup and restore. |
| 24 | Lifecycle Management | Manage application and environment startup, backgrounding, and shutdown. |

## Current source layout

- `app`: Android app, services, settings, bootstrap installation, and session UI.
- `terminal-emulator`: Java VT state machine plus the JNI declaration for PTY/process calls; native process creation currently lives in `src/main/jni/termux.c`.
- `terminal-view`: Android terminal view and drawing/input integration.
- `termux-shared`: shared Android and Termux utilities, including native local-socket code.
- The Termux package ecosystem and bootstrap archives are maintained separately in `termux/termux-packages`.

## Target architecture

### Termux Next Generation design targets

| Aspect | Target | Compatibility boundary |
| --- | --- | --- |
| Core | Rust | Migrate incrementally; keep existing code paths available until behavior is equivalent. |
| Android integration | Kotlin + JNI + Android NDK | Kotlin owns Android lifecycle and UI; JNI exposes a small, versioned interface to Rust. |
| Unix compatibility | Preserve the existing Unix-oriented environment | Keep shell-facing paths, environment, signals, PTYs, and package behavior compatible. |
| Package ecosystem | APT/dpkg | Preserve Termux package formats, repositories, `$PREFIX`, and bootstrap compatibility. |
| Shells | Bash, Zsh, Fish | Continue providing them through the package ecosystem; do not hard-code one shell. |
| Linux distributions | Ubuntu by default; Debian and Alpine remain optional | Run as user-space environments through PRoot-Distro, not as Android system distributions; first-run Ubuntu setup needs an internet connection and app storage. |
| CPU architecture | ARM64, ARMv7, x86_64 | Keep the existing `x86` build as an additional compatibility ABI while it remains supported by the project. |
| Root access | Not required | Core app and standard shell workflows must work without root. Root-only features, if any, remain optional. |
| Memory | Optimize | Measure baseline and per-session use on devices before setting budgets or changing allocators. |
| Startup | Optimize | Measure cold start and first usable prompt before setting targets; keep slow setup and I/O off the Android main thread. |
| Terminal | Responsive and efficient | Preserve terminal semantics and input/accessibility behavior while profiling parsing, scrollback, and rendering. |

### Component choices

| Area | Target | Compatibility boundary |
| --- | --- | --- |
| Android UI | Kotlin with a narrow Rust JNI bridge | Android lifecycle, accessibility, touch, IME, and Canvas stay on the Android side. |
| Application core | Rust `cdylib` | Expose a small, versioned JNI API; avoid passing Android framework objects through the native core. |
| Async work | Tokio for background I/O and jobs | Never block the Android main thread on a Tokio runtime. Use messages/callbacks to return results. |
| Terminal state | libvterm through FFI, owned by Rust | Keep the existing Java emulator available until escape-sequence, resize, scrollback, and input behavior match. VTE is not the initial Android target. |
| Rendering | Custom cell/damage model from Rust, Android Canvas output first | Preserve text selection, cursor, links, font metrics, accessibility, and IME behavior. Move pixel drawing to native code only if measurements justify it. |
| Process and PTY | Rust plus libc behind the existing JNI contract | Keep the current PTY launcher as a fallback until process groups, signals, environment, working directory, resize, exit status, and FD cleanup match. |
| Package management | Existing APT/dpkg and bootstrap flow | Do not replace package formats, repository metadata, `$PREFIX` layout, or package ABI. Keep shell tools installable through Termux packages. |
| Memory | Rust's default allocator initially | Do not replace the global allocator without device profiling and ABI-specific measurements. |
| Storage | SQLite for new structured app data; filesystem for shell-facing files | Keep `~/.termux`, `termux.properties`, themes, fonts, scripts, and package state readable as files. Add migrations and export before moving existing data. |
| Archives | zstd/libarchive for app-owned archive operations | Keep APT/dpkg responsible for Debian package installation and preserve the existing bootstrap archive format until a concrete feature requires a change. |
| Build | Gradle plus Cargo and the Android NDK | Produce and package all four existing ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`, and `x86`. |

### Process supervision capabilities

The process manager may supervise SkyTerm-managed sessions and processes while preserving normal Unix process behavior. Its planned capabilities are:

- Detect when a managed process exits unexpectedly and report its exit status or signal when available.
- Identify zombie and orphan processes, and show their parent-child relationships.
- Track CPU use and flag managed processes that exceed configurable thresholds.
- Record when Android terminates the app process and recover managed-session state where Android permits it. The exact cause of an abrupt Android kill is best-effort because Android may not give the app a final callback.
- Apply an explicit restart policy to selected managed processes, with retry limits and backoff to avoid restart loops.
- Adjust the number of managed processes using device resource conditions and configured limits.

The app now includes a read-only process and resource monitor for terminal sessions. It shows PID/PPID relationships, per-process CPU estimates with a configurable high-CPU threshold, visible zombie/orphan states, device RAM and CPU use, and available app-storage space. Restart policies, durable exit history, Android kill-cause attribution, and automatic resource caps remain future work.

Supervision must not silently restart every shell command or interfere with package scripts, job control, signals, process groups, or user-launched background jobs. Zombie reporting should not attempt to kill a zombie; it can only be reaped by its parent or adopted process. Resource limits and restart policies apply to processes started or explicitly registered with SkyTerm, not arbitrary processes elsewhere on Android.

## Stability diagnostics baseline

The app reuses Termux's existing uncaught-crash logger and adds a local diagnostics screen for Android/API compatibility, native library availability, core `$PREFIX` files, Ubuntu/PRoot setup, RAM/storage pressure, network validation, Android battery/notification restrictions, and coarse crash-log classification. The Rust JNI bridge must fail open to the existing Termux runtime if the optional Rust library or its API version is unavailable. Diagnostics are read-only: they do not run APT/dpkg repair commands, rewrite package state, or upload reports. These checks identify likely causes; they do not prove full filesystem integrity or diagnose OEM-specific process killing.

The existing tool ecosystem stays available through APT/dpkg: Bash, Zsh, Fish, Python, Node.js, Java, C/C++, GCC/Clang, Git, GNU coreutils, and POSIX utilities. Ubuntu, Debian, and Alpine environments are user-managed through compatible user-space tooling such as `proot-distro`; availability and behavior depend on the selected architecture and distribution configuration.

## Migration stages

1. **Foundation:** add the Kotlin-to-Rust JNI bridge and Cargo build integration without changing terminal behavior.
2. **Process/PTY:** port isolated native operations while preserving JNI signatures and retain the existing C launcher as a fallback.
3. **Terminal state:** feed the same terminal streams to the Rust/libvterm path and compare state updates before switching the default.
4. **Rendering:** adapt the Rust cell/damage model to the Android view; preserve Android input, selection, and accessibility behavior.
5. **Persistence and archives:** introduce SQLite and archive libraries only for concrete app-owned features, with filesystem export and existing package tools intact.
6. **Fork identity and release:** choose a separate application ID/signing identity if the custom app must coexist with Termux, and rebuild bootstrap/packages if the package namespace changes.

## Build baseline

This checkout is the official `v0.119.0-beta.3` source matching the supplied APK, at commit `e634d8f981f48b6b89202cf0e04533f0889e03b3`. Its original build pinned Android Gradle Plugin `4.2.2`, Gradle wrapper `7.2`, compile SDK `30`, target SDK `28`, and NDK `22.1.7171670`.

The local build configuration now uses Android Gradle Plugin `8.2.2`, Gradle `8.5`, Kotlin Gradle Plugin `2.0.21`, compile SDK `30`, target SDK `28`, and the installed NDK `28.2.13676358`. This lets the project use the workstation's JDK `21`; Gradle added supported Java 21 runtime use in version `8.5`. Existing compile and target SDK levels remain unchanged to keep Android API behavior stable during the first migration.

The first Kotlin-to-Rust JNI bridge is wired into app startup and reports API version `1`. The Gradle task compiles and packages the Rust `cdylib` for all four existing Android ABIs. Rust targets installed for this workstation are `aarch64-linux-android`, `armv7-linux-androideabi`, `i686-linux-android`, and `x86_64-linux-android`; on a new workstation, install them with:

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
```

`.\gradlew.bat :app:assembleDebug` succeeded and produced the universal debug APK at `app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk` plus four ABI-specific APKs. Shell launch, terminal parsing, rendering, APT/dpkg, and filesystem data remain on their existing implementations. The test suite and on-device runtime behavior have not been exercised.

Each migration stage needs compatibility coverage before the replacement becomes the default path.
