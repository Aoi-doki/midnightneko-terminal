#!/usr/bin/env bash
#
# Build Mayonaka locally on Arch Linux.
#
#   ./build.sh              # release APK, arm64-v8a, signed with mayonaka.jks
#   ./build.sh debug        # debug APK (no R8, builds much faster)
#   ./build.sh clean        # wipe build output and the downloaded bootstrap
#   ./build.sh install      # build release and adb install -r onto the connected device
#   ./build.sh deps         # print the AUR/pacman packages needed and exit
#
# The APK ends up in app/build/outputs/apk/<type>/ and is always signed with the committed
# keystore, so it installs over the top of the previous build without losing $HOME.

set -euo pipefail

cd "$(dirname "$(readlink -f "$0")")"

V=$'\033[38;2;139;92;246m'
RED=$'\033[38;2;224;85;97m'
DIM=$'\033[38;2;154;149;181m'
RST=$'\033[0m'

step() { printf '%s::%s %s\n' "$V" "$RST" "$*"; }
die()  { printf '%s::%s %s\n' "$RED" "$RST" "$*" >&2; exit 1; }
info() { printf '   %s%s%s\n' "$DIM" "$*" "$RST"; }

PACKAGES=(
    "jdk21-openjdk    (pacman)  -- the build needs JDK 21"
    "android-sdk      (AUR)     -- yay -S android-sdk"
    "android-sdk-platform-tools (AUR)"
    "android-sdk-build-tools    (AUR)"
    "android-platform (AUR)     -- provides the compileSdk platform"
    "android-ndk      (AUR)     -- yay -S android-ndk"
    "unzip curl       (pacman)"
)

print_deps() {
    step "Arch packages"
    for p in "${PACKAGES[@]}"; do info "$p"; done
    echo
    info "yay -S android-sdk android-sdk-platform-tools android-sdk-build-tools android-platform android-ndk"
    info "sudo usermod -aG android-sdk \"\$USER\"   # then log out and back in"
    echo
    info "The AUR android-sdk installs to /opt/android-sdk. If yours is elsewhere, export"
    info "ANDROID_HOME before running this script."
}

# --------------------------------------------------------------------------------------------
# Toolchain discovery
# --------------------------------------------------------------------------------------------

find_sdk() {
    if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then
        echo "$ANDROID_HOME"; return
    fi
    if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT" ]; then
        echo "$ANDROID_SDK_ROOT"; return
    fi
    for candidate in /opt/android-sdk "$HOME/Android/Sdk" "$HOME/android-sdk"; do
        [ -d "$candidate" ] && { echo "$candidate"; return; }
    done
    return 1
}

find_ndk() {
    local sdk="$1" wanted="$2"
    # A matching version inside the SDK is preferred, since that is what Gradle looks for.
    [ -d "$sdk/ndk/$wanted" ] && { echo "$sdk/ndk/$wanted"; return; }
    if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
        echo "$ANDROID_NDK_HOME"; return
    fi
    # The AUR android-ndk package.
    [ -d /opt/android-ndk ] && { echo /opt/android-ndk; return; }
    # Any NDK the SDK happens to have.
    if [ -d "$sdk/ndk" ]; then
        local any
        any="$(find "$sdk/ndk" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -1)"
        [ -n "$any" ] && { echo "$any"; return; }
    fi
    return 1
}

setup() {
    step "Checking the toolchain"

    command -v java > /dev/null 2>&1 || die "java not found -- pacman -S jdk21-openjdk"

    local java_major
    java_major="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
    if [ -n "$java_major" ] && [ "$java_major" -lt 17 ]; then
        die "JDK $java_major is too old; Android Gradle Plugin 8.x needs JDK 17+ (21 recommended).
   Try: sudo archlinux-java set java-21-openjdk"
    fi
    info "java $java_major"

    SDK="$(find_sdk)" || die "Android SDK not found.
   Run './build.sh deps' for the package list, or export ANDROID_HOME=/path/to/sdk"
    info "sdk  $SDK"

    local ndk_version
    ndk_version="$(sed -n 's/^ndkVersion=//p' gradle.properties)"

    local ndk
    if ndk="$(find_ndk "$SDK" "$ndk_version")"; then
        info "ndk  $ndk"
        # Gradle resolves the NDK by version under $SDK/ndk unless told otherwise. Point it at
        # whatever was found so an AUR NDK at /opt/android-ndk works too.
        export ANDROID_NDK_HOME="$ndk"
        NDK_DIR_ARG="-Pandroid.ndkPath=$ndk"
    else
        die "NDK not found (wanted $ndk_version).
   yay -S android-ndk, or: sdkmanager --install 'ndk;$ndk_version'"
    fi

    export ANDROID_HOME="$SDK"
    export ANDROID_SDK_ROOT="$SDK"

    # local.properties is gitignored; write it so Android Studio agrees with this script.
    if [ ! -f local.properties ] || ! grep -q "^sdk.dir=$SDK\$" local.properties; then
        echo "sdk.dir=$SDK" > local.properties
        info "wrote local.properties"
    fi
}

# --------------------------------------------------------------------------------------------

apk_path() {
    find "app/build/outputs/apk/$1" -name '*.apk' -print -quit 2> /dev/null
}

report() {
    local apk
    apk="$(apk_path "$1")"
    [ -n "$apk" ] || die "no APK produced in app/build/outputs/apk/$1"
    echo
    step "Built"
    info "$apk"
    info "$(du -h "$apk" | cut -f1)"
    if command -v apksigner > /dev/null 2>&1; then
        apksigner verify --print-certs "$apk" 2> /dev/null | sed -n 's/^/   /p' | head -6 || true
    fi
    echo
    info "adb install -r \"$apk\""
}

case "${1:-release}" in
    deps)
        print_deps
        ;;
    clean)
        setup
        step "Cleaning"
        ./gradlew clean
        rm -f app/src/main/cpp/bootstrap-*.zip
        ;;
    debug)
        setup
        step "Building the debug APK"
        # shellcheck disable=SC2086
        ./gradlew :app:assembleDebug $NDK_DIR_ARG "${@:2}"
        report debug
        ;;
    release)
        setup
        step "Building the release APK"
        # shellcheck disable=SC2086
        ./gradlew :app:assembleRelease $NDK_DIR_ARG "${@:2}"
        report release
        ;;
    install)
        setup
        step "Building the release APK"
        # shellcheck disable=SC2086
        ./gradlew :app:assembleRelease $NDK_DIR_ARG
        report release
        command -v adb > /dev/null 2>&1 || die "adb not found -- yay -S android-sdk-platform-tools"
        step "Installing"
        adb install -r "$(apk_path release)"
        ;;
    *)
        die "unknown command '$1'. Try: release | debug | clean | install | deps"
        ;;
esac
