"""Shared authenticated GitHub asset operations for DomainGuard release and preview tools."""

import hashlib
import json
import os
import re
import subprocess
import time
import urllib.error
import urllib.request

DOWNLOADS = "phooBarred/DomainGuard-downloads"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)

def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Authenticated API calls must stay at their explicitly selected endpoint."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

class GitHub:
    def __init__(self, token):
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, route, data=None, *, method=None, upload=None, optional=False):
        url = "https://api.github.com" + route
        headers = {"Authorization": "Bearer " + self.token,
                   "Accept": "application/vnd.github+json",
                   "User-Agent": "DomainGuard-release",
                   "X-GitHub-Api-Version": "2022-11-28"}
        if upload is not None:
            require(re.fullmatch(r"/repos/" + re.escape(DOWNLOADS) +
                                r"/releases/\d+/assets\?name=[A-Za-z0-9.-]+", route),
                    "Unexpected asset upload destination.")
            url = "https://uploads.github.com" + route
            data = upload.read_bytes()
            headers["Content-Type"] = {".apk": "application/vnd.android.package-archive",
                                       ".zip": "application/zip", ".txt": "text/plain", ".pdf": "application/pdf"}.get(
                                           upload.suffix, "application/octet-stream")
        elif data is not None:
            data = json.dumps(data).encode("utf-8")
            headers["Content-Type"] = "application/json"
        try:
            with self.opener.open(urllib.request.Request(
                    url, data=data, headers=headers, method=method), timeout=60) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if optional and error.code == 404:
                return None
            raise RuntimeError(f"GitHub {method or 'GET'} {route}: HTTP {error.code}") from None

def github_credentials(root):
    result = subprocess.run(
        ["git", "-c", "credential.interactive=never", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n", text=True, capture_output=True,
        cwd=root, timeout=25,
        env=dict(os.environ, GIT_TERMINAL_PROMPT="0", GCM_INTERACTIVE="never"))
    require(result.returncode == 0, "GitHub credentials unavailable in Git Credential Manager.")
    values = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
    require(values.get("password"), "GitHub credentials unavailable.")
    return GitHub(values["password"])

def verify_download(url, expected_hash):
    # Anonymous requests: never send GitHub credentials to a download/CDN URL.
    require(url.startswith(f"https://github.com/{DOWNLOADS}/releases/download/"),
            "Unexpected public download URL.")
    for attempt in range(6):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "DomainGuard-release-verification"})
            with urllib.request.urlopen(request, timeout=60) as response:
                received = hashlib.file_digest(response, "sha256").hexdigest()
            require(received == expected_hash, "Public download checksum does not match.")
            return
        except urllib.error.HTTPError as error:
            if error.code != 404 or attempt == 5:
                raise RuntimeError(f"Public download failed: HTTP {error.code}") from None
            time.sleep(5)
