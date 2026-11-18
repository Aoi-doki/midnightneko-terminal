# Ye Olde Keys

A tiny Android keyboard that turns whatever you type into formal Ye Olde English the moment you
finish a word.

```
you type                                     the field shows
hey! what's up? are you coming to my house?  hail! what news? art thou coming unto mine abode?
I love you so much                           I love thee most heartily
do you know what he wants                    dost thou know what he wanteth
```

It is a separate app from Mayonaka (the Termux fork at the repository root) and builds on its own,
the same way `arise/` does. Nothing here touches the terminal.

## Install

Build it (below), or take the `yeolde-apk` artifact from the **yeolde** workflow run, then:

1. Open **Ye Olde Keys** and tap **Open keyboard settings**; switch the keyboard on.
2. Tap **Choose keyboard** (or the globe key on another keyboard) and pick **Ye Olde Keys**.

The app also has a preview box, so you can see the translation without switching keyboards.

## How it behaves

- Each time you finish a word (space, punctuation, Enter) the sentence you are writing is
  re-translated in place. Only the part that changed is retyped, so your caret and the rest of the
  text are left alone.
- A few rules need the *next* word, so they settle one word later: `my` becomes `mine` before a
  vowel, `a` becomes `an`, `do you` becomes `dost thou` once `you` arrives. A lone `no` becomes
  `nay` only when punctuation follows it (otherwise "no problem" would break).
- The **Olde · ON/OFF** pill on the keyboard's top strip switches translation off when you need to
  write something literally.
- Passwords, e-mail, web addresses, numbers and raw terminals (including Mayonaka's) are never
  translated.

## What it knows

`OldeEnglish.kt` is a pure function with no Android dependencies:

- **thou / thee / thy / thine / ye**, picked from the words around them
- **verb endings**: `thou knowest`, `he goeth`, `thou hast`, `dost thou`, `wilt thou`
- **inverted questions**: `are you` -> `art thou`
- **contractions**: `it's` -> `'tis`, `don't` -> `do not`, `I can't` -> `I cannot`
- **vocabulary**: `yes` -> `aye`, `please` -> `prithee`, `before` -> `ere`, `house` -> `abode`...
- **phrases**: `good morning` -> `good morrow`, `see you later` -> `until we meet again`

Every rule is idempotent, which is what lets the keyboard safely re-run it over the same sentence.
To teach it a word, add it to `VOCAB`; to teach it a verb, add it to `VERBS`.

## Build

JDK 21 and the Android SDK (platform 36, build-tools 36.0.0):

```
cd yeolde
./gradlew testDebugUnitTest        # the engine and the live-typing tests
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
```

The APK is signed with the committed `yeolde.jks` (password `yeoldekeys`), like `mayonaka.jks`, so
every build installs over the last. It is about 60 KB: no libraries, no network, no permissions.
