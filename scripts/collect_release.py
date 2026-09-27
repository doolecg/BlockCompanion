"""Collect the six BlockCompanion release jars into build/release/<version>/ and check each one.

    python scripts/collect_release.py            # after ./gradlew build

Reads mod_version from gradle.properties, copies the shipping jar of every loader module (the remapped plain jar on
1.21.1, the shadowJar plain jar on 26.x; never -dev, -dev-shadow, -sources or common jars), and checks that each
carries the right version in its metadata and the core classes. Prints size and sha256. Exits non-zero if any jar is
missing or wrong.
"""
import hashlib
import os
import re
import shutil
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# (module dir, jar name without "-<version>.jar", metadata file inside the jar, how the version is written there)
MODULES = [
    ("mc-1.21.1/fabric", "blockcompanion-fabric-1.21.1", "fabric.mod.json", r'"version"\s*:\s*"([^"]+)"'),
    ("mc-1.21.1/neoforge", "blockcompanion-neoforge-1.21.1", "META-INF/neoforge.mods.toml", r'(?m)^version\s*=\s*"([^"]+)"'),
    ("mc-26.2/fabric", "blockcompanion-fabric-26.2", "fabric.mod.json", r'"version"\s*:\s*"([^"]+)"'),
    ("mc-26.3/fabric", "blockcompanion-fabric-26.3", "fabric.mod.json", r'"version"\s*:\s*"([^"]+)"'),
    ("mc-26.3/neoforge", "blockcompanion-neoforge-26.3", "META-INF/neoforge.mods.toml", r'(?m)^version\s*=\s*"([^"]+)"'),
    ("paper", "blockcompanion-paper", "plugin.yml", r"(?m)^version:\s*'?\"?([^'\"\s]+)"),
]
CORE_PREFIX = "io/blockcompanion/core/"


def mod_version():
    text = open(os.path.join(ROOT, "gradle.properties"), encoding="utf-8").read()
    m = re.search(r"(?m)^mod_version\s*=\s*(\S+)", text)
    if not m:
        sys.exit("No mod_version in gradle.properties.")
    return m.group(1)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def check(jar, meta, pattern, version):
    """Problems with the jar, or an empty list."""
    problems = []
    with zipfile.ZipFile(jar) as z:
        names = z.namelist()
        if meta not in names:
            return [f"no {meta} inside"]
        m = re.search(pattern, z.read(meta).decode("utf-8", errors="replace"))
        found = m.group(1) if m else None
        if found != version:
            problems.append(f"{meta} says version {found!r}, expected {version!r}")
        core = sum(1 for n in names if n.startswith(CORE_PREFIX) and n.endswith(".class"))
        if core == 0:
            problems.append(f"no {CORE_PREFIX} classes inside")
    return problems


def main():
    version = mod_version()
    out = os.path.join(ROOT, "build", "release", version)
    os.makedirs(out, exist_ok=True)
    failed = []
    print(f"BlockCompanion {version} -> {out}")
    for module, base, meta, pattern in MODULES:
        name = f"{base}-{version}.jar"
        src = os.path.join(ROOT, module, "build", "libs", name)
        if not os.path.isfile(src):
            failed.append(f"{name}: missing {src} (run ./gradlew build)")
            continue
        problems = check(src, meta, pattern, version)
        if problems:
            failed.append(f"{name}: " + "; ".join(problems))
            continue
        dst = os.path.join(out, name)
        shutil.copy2(src, dst)
        print(f"  ok  {name:45} {os.path.getsize(dst):>9,} B  sha256 {sha256(dst)}")
    stray = sorted(set(os.listdir(out)) - {f"{b}-{version}.jar" for _, b, _, _ in MODULES})
    if stray:
        print("  note: other files in the folder (not part of the release):", ", ".join(stray))
    if failed:
        print("FAILED:", file=sys.stderr)
        for f in failed:
            print("  " + f, file=sys.stderr)
        sys.exit(1)
    print(f"all {len(MODULES)} jars collected and checked")


if __name__ == "__main__":
    main()
