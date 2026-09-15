# Mayonaka （真夜中）

A single-APK Termux fork for a Galaxy S25 Ultra: unrooted, arm64, Android 15+.

Mayonaka is [termux-app](https://github.com/termux/termux-app) with **Termux:API**,
**Termux:Widget**, **Termux:Styling** and **Termux:Boot** folded into the same APK, themed to a
midnight-neko palette, and shipped pre-configured — the terminal comes up already styled, with a
provisioning script waiting in `$HOME`, instead of needing an afternoon of setup.

```
   background  #0a0a0f      accent  #8B5CF6
   surface     #1a1a24      muted   #2a2a3a
   foreground  #e4e2f0
```

---

## Read this before installing

### It replaces official Termux

The `applicationId` stays **`com.termux`**, and that is not negotiable. Every package in the
Termux ecosystem is compiled with `/data/data/com.termux/files/usr` baked into its binaries — a
renamed package would break `pkg` and every program it installs. Mayonaka therefore changes the
app **label** and **icon** only.

Because the package name is the same, Android treats Mayonaka and official Termux as the same app.
You cannot have both. Installing Mayonaka means uninstalling Termux first, and **uninstalling
Termux deletes `$HOME` and `$PREFIX`**.

👉 **Back up first: [MIGRATE.md](MIGRATE.md)** has the exact `tar` commands.

### The add-on APKs stop working

Mayonaka is signed with its own key (`mayonaka.jks`, committed in this repo), not the F-Droid one.
All four Termux add-ons declare `android:sharedUserId="com.termux"`, and Android only lets apps
share a user id when they are signed with the same certificate. So:

- **F-Droid Termux:API, Termux:Widget, Termux:Boot and Termux:Styling will refuse to install
  alongside Mayonaka, and existing installs will stop working.** Uninstall them.
- That is fine — all four are merged into this APK. You get the receiver, the widget, the boot
  hook and the styling picker from the one app.

### The keystore is committed on purpose

`mayonaka.jks` and its password (`midnightneko`) are in the repository. This is a personal build:
the alternative is that CI generates a fresh key on every run, the signature changes, and updates
can only be installed by uninstalling — which takes `$HOME` with it. A stable key that anyone can
read beats losing the home directory on every update.

Nothing else should ever be signed with it.

---

## What is in the APK

| Merged from | Package | What you get |
|---|---|---|
| termux-app | `com.termux.app` | the terminal itself |
| termux-boot | `com.termux.boot` | `~/.termux/boot/` scripts run at boot |
| termux-widget | `com.termux.widget` | home-screen widget + launcher shortcuts from `~/.shortcuts` |
| termux-styling | `com.termux.styling` | 114 colour schemes and 26 fonts, as an in-app screen |
| termux-api | `com.termux.api` | the `termux-*` commands (battery, clipboard, camera, sensors, …) |

The whole of termux-styling's asset set comes across: `assets/colors/` and `assets/fonts/`.
Upstream's directories hold 122 and 38 *files*, which works out at **114 colour schemes** and
**26 fonts** once the `.txt` licence files sitting beside them are discounted — those are still
shipped, and long-pressing an entry in either picker shows its licence. The fonts are what make
the APK large (~64 MB of the ~68 MB).

Baked into the APK and written to `~/.termux` on first run (never overwriting your edits):

- `colors.properties` — the midnight-neko terminal palette
- `font.ttf` — JetBrainsMono Nerd Font Mono
- `termux.properties` — extra-keys layout, cursor, margins, shortcuts
- `~/setup.sh` — the provisioning script, offered on first launch

---

## The termux-api component patch

This is the one genuinely fiddly part of the merge, and it is handled automatically.

The `termux-api` **package** (the CLI half, installed with `pkg install termux-api`) ships
`$PREFIX/libexec/termux-api`, a small C program that broadcasts to a component string compiled
into the binary:

```c
/* termux-api-package/termux-api.c, child_argv[5] */
"com.termux.api/.TermuxApiReceiver"
```

Once the receiver lives inside the `com.termux` app, that component no longer exists and every
`termux-*` command hangs forever.

The fix: Mayonaka declares the receiver as the class **`com.termux.api.TermuxApiReceiver`**, so
its component is `com.termux/.api.TermuxApiReceiver`:

```
com.termux.api/.TermuxApiReceiver     33 bytes   (stock)
com.termux/.api.TermuxApiReceiver     33 bytes   (Mayonaka)
```

Identical length — so the installed binary can be patched **in place**, with no relocation and no
size change. `setup.sh` installs `$PREFIX/bin/mayonaka-patch-api`, which does a binary-safe
`python3` replace, verifies the result, and writes through a temporary file so a failure can never
leave a half-written binary. An apt hook at
`$PREFIX/etc/apt/apt.conf.d/99-mayonaka-api-patch` re-applies it after every package operation,
because `pkg upgrade termux-api` restores the pristine binary.

Run it by hand any time:

```sh
mayonaka-patch-api
```

---

## First run

1. Install the APK. The bootstrap unpacks; the midnight palette, font and `termux.properties` are
   written to `~/.termux`.
2. Mayonaka offers to run `~/setup.sh`. Say yes (it needs network and a few minutes), or run it
   later with `bash ~/setup.sh`, or from **Settings → Mayonaka → Re-run provisioning**.

`setup.sh` is non-interactive, idempotent and safe to re-run. It never overwrites a config file
you have edited. It:

- installs `fish starship fastfetch git openssh tmux fzf ripgrep bat eza zoxide micro curl wget
  jq python nodejs-lts termux-api termux-tools openssl`
- makes **fish** the login shell, with a themed config: starship prompt, zoxide, fzf, aliases
- writes a **starship** config — violet pill prompt, powerline segments on
  `#8B5CF6` / `#2a2a3a` / `#1a1a24`, Nerd Font glyphs
- writes a **tmux** config in the same palette
- writes a **fastfetch** config using the neko art: the art goes to
  `~/.config/fastfetch/neko.txt`, a truecolor violet (139, 92, 246) copy is generated as
  `neko-violet.txt`, and fastfetch points at that with `"type": "file-raw"`. Aliased `nyafetch`
  and run on shell start.
- scaffolds `~/.shortcuts/` for the widget with working scripts
- adds `~/.ssh/config` host `victus` → `100.83.14.4`
- installs and applies the termux-api binary patch and its apt hook

Environment knobs: `MAYONAKA_FORCE=1` overwrites the configs it owns, `MAYONAKA_NO_PKG=1` skips
the package install.

---

## The keyboard

The stock extra-keys row is flat and floats on the background. Mayonaka gives every key a real
bordered chip: rounded rect, 1dp `#8B5CF6` stroke at 35% alpha, `#12121a` fill, violet fill when
pressed or active, with a subtle top divider so the row reads as a bar rather than loose buttons.

Default layout:

```
ESC   |   /   HOME   UP     END     PGUP   DEL
TAB   CTRL   ALT   LEFT   DOWN   RIGHT   PGDN   BKSP
```

A third symbol row (`- _ = + { } [ ] ; ' " ` ~ < >`) can be toggled on in settings.

**Settings → Mayonaka** has: colour scheme picker (every merged scheme), font picker (every
merged font), keyboard style (bordered / flat / hidden), extra-keys row count, cursor style and
blink, terminal opacity, and a re-run provisioning button.

---

## Building

### On Arch

```sh
yay -S android-sdk android-sdk-platform-tools android-sdk-build-tools android-platform android-ndk
sudo usermod -aG android-sdk "$USER"   # log out and back in

./build.sh deps        # print the package list again
./build.sh             # release APK, arm64-v8a, signed
./build.sh debug       # faster, no R8
./build.sh install     # build + adb install -r
./build.sh clean
```

The script finds the SDK at `$ANDROID_HOME`, `/opt/android-sdk` or `~/Android/Sdk`, and the NDK
at `$SDK/ndk/<version>`, `$ANDROID_NDK_HOME` or `/opt/android-ndk`.

### In CI

`.github/workflows/build.yml` runs on every push: JDK 21, Android SDK + the pinned NDK,
`./gradlew :app:assembleRelease`, arm64-v8a only. It verifies the APK's signing certificate
matches `mayonaka.jks` before uploading, so a signature change can never sneak through. The APK
lands as the `mayonaka-apk` run artifact.

Only `arm64-v8a` is built (`MAYONAKA_ABI` overrides it), which means one bootstrap archive in the
APK instead of four.

---

## Layout

```
app/                        the single merged application module
  src/main/assets/mayonaka/ colours, font, termux.properties, setup.sh
  src/main/java/com/termux/
    app/                    the terminal (from termux-app)
    api/                    from termux-api
    boot/                   from termux-boot
    styling/                from termux-styling
    widget/                 from termux-widget
    mayonaka/               Mayonaka's own code: defaults, settings, keyboard styling
terminal-emulator/          unchanged from termux-app
terminal-view/              unchanged from termux-app
termux-shared/              shared library, lightly retouched for the midnight palette
art/generate_icons.py       regenerates every launcher asset from one geometry definition
mayonaka.jks                the signing key (see above)
```

---

## Licence

Mayonaka inherits its licences from upstream: termux-app, termux-api, termux-widget,
termux-styling and termux-boot are all GPLv3 / MIT as published by the Termux project. See
[LICENSE.md](LICENSE.md). Enormous thanks to the Termux maintainers — this is their work, wearing
a different coat.
