"""Publish a BlockCompanion GitHub release: the top section of RELEASE_NOTES.md plus the seven jars.

    python scripts/publish_release.py --dry-run   # check everything, change nothing
    python scripts/publish_release.py             # create the release (tag on main) and upload the jars

Run collect_release.py first: the jars are taken from build/release/<mod_version>/. The version comes from
gradle.properties; the tag (plain, e.g. 0.2.0) must already be pushed to origin and local HEAD must equal origin/main.
The release is titled "BlockCompanion <version>", its body is RELEASE_NOTES.md above the first ---, and it is marked
latest. `gh` isn't needed: this uses the GitHub REST API with the token git already stores (git credential fill); the
token is never printed. If the release already exists (say an upload failed half way), only missing jars are uploaded.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

REPO = "doolecg/BlockCompanion"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JARS = [
    "blockcompanion-fabric-1.21.1-{v}.jar",
    "blockcompanion-neoforge-1.21.1-{v}.jar",
    "blockcompanion-fabric-26.2-{v}.jar",
    "blockcompanion-neoforge-26.2-{v}.jar",
    "blockcompanion-fabric-26.3-{v}.jar",
    "blockcompanion-neoforge-26.3-{v}.jar",
    "blockcompanion-paper-{v}.jar",
]


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()


def token():
    out = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n\n",
                         capture_output=True, text=True, check=True, cwd=ROOT).stdout
    for line in out.splitlines():
        if line.startswith("password="):
            return line[len("password="):]
    sys.exit("No GitHub credential stored for git (push something once so git's credential manager saves one).")


def call(method, url, tok, body=None, data=None, ctype="application/json", missing_ok=False):
    payload = data if data is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(url, method=method, data=payload)
    req.add_header("Authorization", "Bearer " + tok)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    req.add_header("User-Agent", "BlockCompanion-release")
    if payload is not None:
        req.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(req, timeout=900) as r:
            return json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        if missing_ok and e.code == 404:
            return None
        sys.exit(f"{method} {url} -> {e.code}: {e.read().decode(errors='replace')[:500]}")


def mod_version():
    m = re.search(r"(?m)^mod_version\s*=\s*(\S+)", open(os.path.join(ROOT, "gradle.properties"), encoding="utf-8").read())
    if not m:
        sys.exit("No mod_version in gradle.properties.")
    return m.group(1)


def release_notes(version):
    """The newest section of RELEASE_NOTES.md, heading included (the release page shows it like earlier ones)."""
    text = open(os.path.join(ROOT, "RELEASE_NOTES.md"), encoding="utf-8").read().replace("\r\n", "\n")
    section = text.split("\n---\n", 1)[0].strip()
    first = section.splitlines()[0] if section else ""
    if first.strip() != f"# BlockCompanion {version}":
        sys.exit(f"RELEASE_NOTES.md starts with {first!r}, expected '# BlockCompanion {version}'. Write the notes first.")
    return section + "\n"


def main():
    ap = argparse.ArgumentParser(description="Publish the BlockCompanion GitHub release for mod_version.")
    ap.add_argument("--dry-run", action="store_true", help="check everything, change nothing")
    a = ap.parse_args()

    v = mod_version()
    title = f"BlockCompanion {v}"
    body = release_notes(v)
    folder = os.path.join(ROOT, "build", "release", v)
    files = [os.path.join(folder, n.format(v=v)) for n in JARS]
    missing = [f for f in files if not os.path.isfile(f)]
    if missing:
        sys.exit("Missing jars (run ./gradlew build and python scripts/collect_release.py):\n  " + "\n  ".join(missing))

    git("fetch", "--quiet", "origin", "main")
    if git("ls-remote", "--tags", "origin", f"refs/tags/{v}") == "":
        sys.exit(f"Tag {v} isn't on origin (git tag {v} && git push origin refs/tags/{v}).")
    head, remote = git("rev-parse", "HEAD"), git("rev-parse", "origin/main")
    if head != remote:
        sys.exit(f"Local HEAD ({head[:10]}) isn't origin/main ({remote[:10]}): push main first.")

    tok = token()
    existing = call("GET", f"https://api.github.com/repos/{REPO}/releases/tags/{urllib.parse.quote(v)}", tok, missing_ok=True)
    have = {x["name"] for x in existing["assets"]} if existing else set()
    print(f"release   : {title} in {REPO} (tag {v}, main {head[:10]})")
    print("status    :", "exists" if existing else "will be created")
    for f in files:
        n = os.path.basename(f)
        print(f"file      : {n} ({os.path.getsize(f) / 1024:.0f} KB){' already uploaded' if n in have else ''}")
    print(f"notes     : {len(body.splitlines())} lines, starting {body.splitlines()[0]!r}")
    if a.dry_run:
        print("dry run: nothing changed")
        return

    if existing is None:
        existing = call("POST", f"https://api.github.com/repos/{REPO}/releases", tok,
                        {"tag_name": v, "target_commitish": "main", "name": title, "body": body,
                         "draft": False, "prerelease": False, "make_latest": "true"})
        print("created", existing["html_url"])
    upload = existing["upload_url"].split("{")[0]
    for f in files:
        n = os.path.basename(f)
        if n in have:
            continue
        with open(f, "rb") as fh:
            got = call("POST", f"{upload}?name={urllib.parse.quote(n)}", tok, data=fh.read(), ctype="application/java-archive")
        print("uploaded", got["name"], got["size"], got["state"])
    latest = call("GET", f"https://api.github.com/repos/{REPO}/releases/latest", tok)
    print(f"latest    : {latest['tag_name']}")
    print(existing["html_url"])


if __name__ == "__main__":
    main()
