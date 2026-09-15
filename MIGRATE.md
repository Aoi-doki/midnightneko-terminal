# Migrating from Termux to Mayonaka

Mayonaka keeps the `com.termux` package name (it has to — see the README), but it is signed with
a different key. Android will not upgrade an app across a signature change, so the only way in is
**uninstall Termux, install Mayonaka** — and uninstalling Termux deletes `/data/data/com.termux`
entirely, which is where `$HOME` and `$PREFIX` live.

So: back up, uninstall, install, restore. Budget fifteen minutes.

> **Do the backup first, and check the archives exist, before you uninstall anything.** There is
> no undo.

---

## 0. What you are saving

| Path | What it is | Worth keeping? |
|---|---|---|
| `/data/data/com.termux/files/home` (`$HOME`) | your files, configs, ssh keys, repos | **yes** |
| `/data/data/com.termux/files/usr` (`$PREFIX`) | the installed packages | optional |

`$PREFIX` is reproducible: `pkg install` can rebuild it, and Mayonaka's `setup.sh` reinstalls the
whole set anyway. Backing it up is still worth the two minutes — it gets you back to a working
shell without waiting on the network, and it means you can roll back.

`$HOME` is not reproducible. Save it.

---

## 1. Back up

In **Termux** (the old app), before uninstalling.

Grant storage access if you have not already, so the archives land somewhere that survives the
uninstall:

```sh
termux-setup-storage
```

Then:

```sh
mkdir -p /sdcard/termux-backup
cd /data/data/com.termux/files

# $HOME -- the important one.
tar -czf /sdcard/termux-backup/home.tar.gz home

# $PREFIX -- optional, ~1-2 GB depending on what you have installed.
tar -czf /sdcard/termux-backup/usr.tar.gz usr
```

If `tar` complains about files changing while it reads them, stop other sessions first
(`pkill -f tmux`, close extra tabs) and re-run.

### Check the backups before you go any further

```sh
ls -lh /sdcard/termux-backup/
tar -tzf /sdcard/termux-backup/home.tar.gz | head
tar -tzf /sdcard/termux-backup/usr.tar.gz  | head
```

You should see sizes in the hundreds of MB and file listings starting with `home/` and `usr/`.
If either archive is a few hundred bytes, or the listing is empty, **do not continue** — the
backup failed.

### Copy them off the phone as well

`/sdcard` survives a Termux uninstall, but it does not survive a factory reset or a dropped phone.
If the laptop is reachable over Tailscale:

```sh
scp /sdcard/termux-backup/*.tar.gz you@100.83.14.4:~/termux-backup/
```

Or plug in a cable and copy from the Files app.

---

## 2. Note anything outside `$HOME` and `$PREFIX`

These do **not** live in the Termux data directory and are not in the archives:

- **Termux:Boot**, **Termux:Widget**, **Termux:API**, **Termux:Styling** — the separate APKs.
  Uninstall them; Mayonaka merges all four in.
- **Widgets on your home screen** — they will go dead and need re-adding after the install.
- **Storage permission** — has to be re-granted (`termux-setup-storage`).
- **Any `Termux:Tasker` / Tasker profiles** that reference the old add-on components.

---

## 3. Uninstall

Uninstall, in this order:

1. Termux:API, Termux:Widget, Termux:Boot, Termux:Styling (any that are installed)
2. Termux

```
Settings → Apps → Termux → Uninstall
```

Or, with adb from the laptop:

```sh
adb uninstall com.termux.api
adb uninstall com.termux.widget
adb uninstall com.termux.boot
adb uninstall com.termux.styling
adb uninstall com.termux
```

---

## 4. Install Mayonaka

Grab the APK from the `mayonaka-apk` artifact of a green CI run, or build it:

```sh
./build.sh
```

Install it:

```sh
adb install app/build/outputs/apk/release/mayonaka_*.apk
```

…or copy the APK to the phone and tap it (you will need "install unknown apps" for whatever file
manager you use).

Launch it once and let the bootstrap unpack. **Do not run `setup.sh` yet** if you are restoring a
`$PREFIX` backup — restore first, then provision.

---

## 5. Restore

In Mayonaka:

```sh
termux-setup-storage
```

### `$HOME`

```sh
cd /data/data/com.termux/files
# Move the freshly created home aside rather than deleting it, in case the restore goes wrong.
mv home home.fresh
tar -xzf /sdcard/termux-backup/home.tar.gz
ls home
```

Once you are happy: `rm -rf /data/data/com.termux/files/home.fresh`.

### `$PREFIX` (only if you backed it up)

`$PREFIX` cannot be replaced from a shell that is *running out of* `$PREFIX` — you would pull the
rug out from under bash, tar and everything else mid-extraction. Use a **failsafe session**, which
runs the system `/system/bin/sh` and touches nothing in `$PREFIX`:

> Long-press the Mayonaka launcher icon → **Failsafe**.
> (Or: swipe out the left drawer → **New session** → long-press → Failsafe.)

In the failsafe session:

```sh
cd /data/data/com.termux/files
rm -rf usr
/system/bin/tar -xzf /sdcard/termux-backup/usr.tar.gz
```

If `/system/bin/tar` does not exist on your device, use the toybox one:

```sh
toybox tar -xzf /sdcard/termux-backup/usr.tar.gz
```

Then close the failsafe session and open a normal one.

---

## 6. Re-apply the Mayonaka bits

If you restored an old `$PREFIX`, its `termux-api` binary still points at the old component, and
the apt hook is not there. Run the provisioning script — it is idempotent, it will not clobber the
configs you just restored, and it installs and applies the patch:

```sh
bash ~/setup.sh
```

or **Settings → Mayonaka → Re-run provisioning**.

Then check the patch took:

```sh
mayonaka-patch-api            # should say "already patched"
termux-battery-status         # should return JSON, not hang
```

If `termux-battery-status` hangs, the patch did not apply — see the termux-api section of the
README.

Finally:

- re-add your home-screen widgets (long-press home → Widgets → Mayonaka)
- re-grant any permissions the `termux-*` commands ask for on first use
- if you kept your own `~/.termux/colors.properties` or `termux.properties`, Mayonaka leaves them
  alone. To take the midnight defaults instead, delete them and relaunch, or use
  **Settings → Mayonaka** to pick a scheme.

---

## Rolling back to official Termux

Same dance in reverse: back up `$HOME`, uninstall Mayonaka, install Termux from F-Droid, restore
`$HOME`, reinstall the add-on APKs. The `$PREFIX` backup taken from Mayonaka restores cleanly
into Termux — it is the same prefix path and the same packages; only the patched
`$PREFIX/libexec/termux-api` differs, and reinstalling with `pkg install --reinstall termux-api`
puts the stock binary back.

Remember to delete `$PREFIX/etc/apt/apt.conf.d/99-mayonaka-api-patch` and
`$PREFIX/bin/mayonaka-patch-api` so the hook stops running.
