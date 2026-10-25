"""Check one reviewed PDF; --publish uploads it to an existing public release."""

import argparse
import json
from pathlib import Path
import re

from pypdf import PdfReader

from github_assets import DOWNLOADS, github_credentials, require, sha256, verify_download


def release_assets(github, release_id):
    result = {}
    page = 1
    while True:
        assets = github.request(f"/repos/{DOWNLOADS}/releases/{release_id}/assets?per_page=100&page={page}")
        result.update((asset["name"], asset) for asset in assets)
        if len(assets) < 100:
            return result
        page += 1


def publish_preview(github, tag, path, publish=False):
    require(re.fullmatch(r"v[0-9][A-Za-z0-9.-]*", tag), "Specify an existing version tag.")
    require(path.suffix.lower() == ".pdf" and re.fullmatch(r"[A-Za-z0-9.-]+", path.name),
            "Use one PDF with a filename containing only letters, numbers, dots and hyphens.")
    require(len(PdfReader(path).pages) > 0, "The PDF has no pages.")
    digest = "sha256:" + sha256(path)
    size = path.stat().st_size
    api = f"/repos/{DOWNLOADS}"
    repository = github.request(api)
    require(repository["full_name"] == DOWNLOADS and not repository["private"], "Unexpected downloads repository.")
    release = github.request(api + "/releases/tags/" + tag)
    require(not release["draft"] and not release["prerelease"], "Choose an existing public stable release.")
    before = release_assets(github, release["id"])
    asset = before.get(path.name)
    if asset:
        require(asset.get("digest") == digest and asset["size"] == size,
                "An asset with that name has different contents; choose a new filename.")
    if not publish:
        print(f"Checked {path.name} ({size} bytes). Nothing uploaded.")
        return None
    if asset is None:
        asset = github.request(api + f"/releases/{release['id']}/assets?name={path.name}",
                               upload=path, method="POST")
    require(asset["state"] == "uploaded" and asset["size"] == size and asset.get("digest") == digest,
            "Preview upload verification failed.")
    after = release_assets(github, release["id"])
    require(all(name in after and all(after[name].get(key) == original.get(key)
                                     for key in ("id", "size", "digest"))
                for name, original in before.items()), "An existing release asset changed.")
    current = github.request(api + "/releases/tags/" + tag)
    require(all(current.get(key) == release.get(key) for key in
                ("id", "published_at", "tag_name", "name", "body", "target_commitish", "draft", "prerelease")),
            "Release metadata changed during preview upload.")
    verify_download(asset["browser_download_url"], digest.removeprefix("sha256:"))
    return dict(url=asset["browser_download_url"], sha256=digest, bytes=size)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--publish", action="store_true")
    args = parser.parse_args()
    path = args.file.resolve()
    result = publish_preview(github_credentials(Path(__file__).resolve().parents[1]), args.tag, path, args.publish)
    if result:
        path.with_suffix(".publication.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(result["url"])


if __name__ == "__main__":
    main()
