#!/usr/bin/env python3
"""Check that the merged manifest is internally consistent.

Five things go wrong quietly when four apps are folded into one, and this checks all five:

  1. A manifest ``android:name`` that points at a class the APK does not contain. The manifest
     merger does not verify this; you find out when the component is used and the app dies with
     ClassNotFoundException.
  2. An ``@string`` / ``@drawable`` / ``@style`` / ``@xml`` / ``@mipmap`` / ``@color`` /
     ``@layout`` reference in the manifest that no longer resolves.
  3. The termux-api receiver's component string drifting off
     ``com.termux/.api.TermuxApiReceiver`` -- which must stay exactly 33 bytes, the length of the
     string compiled into $PREFIX/libexec/termux-api, or the in-place binary patch cannot work.
  4. More than one launcher entry, i.e. a leftover launcher activity or alias from one of the
     merged add-ons showing up as a second icon in the drawer.
  5. Duplicate resource names across the merged modules, which aapt resolves silently by letting
     one win.

Run it against a built APK::

    python3 tools/verify_merge.py app/build/outputs/apk/release/mayonaka_*.apk

With no argument it finds the most recently built APK itself. Requires the Android SDK: it uses
aapt2 (for the manifest) and dexdump (for the class list), located via $ANDROID_HOME.
"""

import glob
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The component the termux-api CLI broadcasts to after patching, and the stock one it ships with.
PATCHED_COMPONENT = "com.termux/.api.TermuxApiReceiver"
STOCK_COMPONENT = "com.termux.api/.TermuxApiReceiver"

failures = []
notes = []


def fail(message):
    failures.append(message)


def note(message):
    notes.append(message)


# --------------------------------------------------------------------------------------------
# SDK tools
# --------------------------------------------------------------------------------------------

def sdk_root():
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(var)
        if value and os.path.isdir(value):
            return value
    local_properties = os.path.join(REPO_ROOT, "local.properties")
    if os.path.isfile(local_properties):
        for line in open(local_properties):
            if line.startswith("sdk.dir="):
                return line.split("=", 1)[1].strip()
    sys.exit("Android SDK not found: set ANDROID_HOME or write local.properties")


def build_tool(name):
    versions = sorted(glob.glob(os.path.join(sdk_root(), "build-tools", "*")))
    if not versions:
        sys.exit("no build-tools installed in " + sdk_root())
    path = os.path.join(versions[-1], name)
    if not os.path.isfile(path):
        sys.exit("%s not found in %s" % (name, versions[-1]))
    return path


def find_apk():
    candidates = glob.glob(os.path.join(REPO_ROOT, "app/build/outputs/apk/*/*.apk"))
    if not candidates:
        sys.exit("no APK built yet -- run ./gradlew :app:assembleRelease first")
    return max(candidates, key=os.path.getmtime)


# --------------------------------------------------------------------------------------------
# Checks
# --------------------------------------------------------------------------------------------

def manifest_xml(apk):
    out = subprocess.run([build_tool("aapt2"), "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
                         capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit("aapt2 failed:\n" + out.stderr)
    return out.stdout


def source_manifest():
    return ET.parse(os.path.join(REPO_ROOT, "app/src/main/AndroidManifest.xml")).getroot()


def dex_classes(apk):
    """Every class in the APK, as dotted names."""
    # Binary, not text: dex string data is not necessarily valid UTF-8 from Python's point of
    # view, and a stray byte in some library's string pool should not sink the check.
    out = subprocess.run([build_tool("dexdump"), "-f", apk], capture_output=True)
    if out.returncode != 0:
        sys.exit("dexdump failed:\n" + out.stderr.decode("utf-8", "replace")[:2000])
    dump = out.stdout.decode("utf-8", "replace")
    classes = set()
    for match in re.finditer(r"Class descriptor\s*:\s*'L([^;]+);'", dump):
        classes.add(match.group(1).replace("/", "."))
    return classes


def check_class_references(tree_dump, classes, package):
    """Every android:name on a component must name a class that is actually in the APK."""
    # aapt2's xmltree dump gives resolved attribute values, which is what we want: the manifest
    # placeholders and relative ".foo" names have already been expanded.
    component_names = []
    current = None
    for line in tree_dump.splitlines():
        stripped = line.strip()
        element = re.match(r"E: ([\w-]+)", stripped)  # activity-alias has a hyphen
        if element:
            current = element.group(1)
            continue

        # An <activity-alias> android:name is a component name with no class behind it; what has
        # to resolve is its targetActivity.
        if current == "activity-alias":
            target = re.search(
                r'A: http://schemas\.android\.com/apk/res/android:targetActivity\([^)]*\)="([^"]+)"',
                stripped)
            if target:
                component_names.append(("activity-alias target", target.group(1)))
            continue

        if current not in ("activity", "service", "receiver", "provider", "application"):
            continue
        attr = re.search(r'A: http://schemas\.android\.com/apk/res/android:name\([^)]*\)="([^"]+)"', stripped)
        if attr:
            component_names.append((current, attr.group(1)))

    if not component_names:
        fail("could not read any component names out of the manifest")
        return

    for kind, name in component_names:
        # Nested classes are written Outer$Inner in the manifest and the dex alike.
        if name.startswith("."):
            name = package + name
        if name not in classes:
            fail("%s %s is declared in the manifest but is not in the APK" % (kind, name))

    note("%d manifest component class references all resolve" % len(component_names))


def check_resource_references(tree_dump):
    """No @string/@drawable/... reference in the manifest may be unresolved."""
    # aapt2 prints resolved references as (type 0x1) 0x7f... and unresolved ones stay as the
    # literal "@string/foo" text, so an unresolved reference is easy to spot.
    unresolved = re.findall(r'="(@[a-z]+/[A-Za-z0-9_.]+)"', tree_dump)
    if unresolved:
        for reference in sorted(set(unresolved)):
            fail("unresolved manifest resource reference: " + reference)
    else:
        note("all manifest resource references resolve")


def check_api_component(root, classes):
    """The receiver's component must be exactly the 33-byte patched string."""
    receiver = None
    for element in root.iter("receiver"):
        if element.get(ANDROID_NS + "name", "").endswith("api.TermuxApiReceiver"):
            receiver = element
            break

    if receiver is None:
        fail("the termux-api receiver is not declared in the manifest")
        return

    declared = receiver.get(ANDROID_NS + "name")
    if declared != ".api.TermuxApiReceiver":
        fail('the receiver must be declared as ".api.TermuxApiReceiver" to produce the right '
             'component, not "%s"' % declared)

    component = "com.termux/" + declared
    if component != PATCHED_COMPONENT:
        fail("receiver component is %r, expected %r" % (component, PATCHED_COMPONENT))
        return

    if len(component) != len(STOCK_COMPONENT):
        fail("receiver component is %d bytes, but the string compiled into termux-api is %d; "
             "an in-place patch is impossible"
             % (len(component), len(STOCK_COMPONENT)))
        return

    if "com.termux.api.TermuxApiReceiver" not in classes:
        fail("com.termux.api.TermuxApiReceiver is not in the APK")
        return

    note("termux-api receiver component is %s (%d bytes, same as the stock %s)"
         % (component, len(component), STOCK_COMPONENT))


def check_single_launcher(tree_dump):
    """Exactly one thing may show up in the launcher."""
    # Matched on the full attribute value: LEANBACK_LAUNCHER and IOT_LAUNCHER both contain
    # "LAUNCHER" as a substring and are not drawer entries.
    launchers = len(re.findall(r'="android\.intent\.category\.LAUNCHER"', tree_dump))
    if launchers > 1:
        fail("%d LAUNCHER intent filters -- a merged add-on left a second icon behind" % launchers)
    elif launchers == 0:
        fail("no LAUNCHER intent filter: the app would have no icon")
    else:
        note("exactly one launcher entry")


def check_duplicate_resources():
    """No resource name may be declared twice within the app module."""
    res_root = os.path.join(REPO_ROOT, "app/src/main/res")
    seen = {}
    duplicates = []

    for directory in sorted(os.listdir(res_root)):
        path = os.path.join(res_root, directory)
        if not os.path.isdir(path):
            continue
        qualifier = directory.split("-", 1)
        kind = qualifier[0]
        variant = qualifier[1] if len(qualifier) > 1 else ""

        for filename in sorted(os.listdir(path)):
            if kind == "values":
                content = open(os.path.join(path, filename), encoding="utf-8", errors="replace").read()
                for res_type, name in re.findall(
                        r"<(string|color|style|dimen|bool|integer|string-array|array|plurals)\s+name=\"([^\"]+)\"",
                        content):
                    key = (res_type, name, variant)
                    if key in seen:
                        duplicates.append("%s/%s declared in both %s and %s"
                                          % (res_type, name, seen[key], filename))
                    seen[key] = filename
            else:
                key = (kind, os.path.splitext(filename)[0], variant)
                if key in seen:
                    duplicates.append("%s/%s declared twice (%s, %s)"
                                      % (kind, key[1], seen[key], filename))
                seen[key] = filename

    for duplicate in duplicates:
        fail("duplicate resource: " + duplicate)
    if not duplicates:
        note("%d resource names, no duplicates" % len(seen))


# --------------------------------------------------------------------------------------------

def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else find_apk()
    print("APK: %s (%.1f MB)\n" % (apk, os.path.getsize(apk) / 1e6))

    tree_dump = manifest_xml(apk)
    root = source_manifest()
    classes = dex_classes(apk)

    check_class_references(tree_dump, classes, "com.termux")
    check_resource_references(tree_dump)
    check_api_component(root, classes)
    check_single_launcher(tree_dump)
    check_duplicate_resources()

    for message in notes:
        print("  ok    %s" % message)
    for message in failures:
        print("  FAIL  %s" % message)

    print()
    if failures:
        print("%d problem(s)" % len(failures))
        return 1
    print("merged manifest is consistent")
    return 0


if __name__ == "__main__":
    sys.exit(main())
