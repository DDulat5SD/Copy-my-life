"""Downloads the exact model files listed in model-manifest.json from Hugging Face and checks them.

The files are fetched from one fixed revision (a commit of the model repository, never a branch),
and each must have the listed size and SHA-256, so a rebuild can never pick up another model.
A file already present with the right hash is kept. Standard library only, so it runs before
anything else is installed. Exit code 1 on any mismatch or download failure.

Usage: python download_model.py <target folder>   (Dockerfile: /models/multilingual-minilm)
"""

from __future__ import annotations

import hashlib
import json
import os
import sys
import time
import urllib.request
from pathlib import Path

MANIFEST = Path(__file__).resolve().parent / "model-manifest.json"
CHUNK = 1 << 20
ATTEMPTS = 3


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify(path: Path, size: int, sha256: str) -> str | None:
    """None when the file is the expected one, otherwise what is wrong with it."""
    if not path.is_file():
        return "missing"
    if path.stat().st_size != size:
        return f"size {path.stat().st_size}, expected {size}"
    actual = sha256_of(path)
    if actual != sha256:
        return f"sha256 {actual}, expected {sha256}"
    return None


def url_for(manifest: dict, path: str) -> str:
    return f"https://huggingface.co/{manifest['repo']}/resolve/{manifest['revision']}/{path}"


def download(url: str, target: Path, size: int, sha256: str) -> None:
    partial = target.with_name(target.name + ".part")
    target.parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(1, ATTEMPTS + 1):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "copymylife-ai-build"})
            with urllib.request.urlopen(request, timeout=60) as response, partial.open("wb") as file:
                for chunk in iter(lambda: response.read(CHUNK), b""):
                    file.write(chunk)
            problem = verify(partial, size, sha256)
            if problem is None:
                os.replace(partial, target)
                return
            print(f"  attempt {attempt}: {problem}", flush=True)
        except OSError as error:
            print(f"  attempt {attempt}: {error}", flush=True)
        time.sleep(2 * attempt)
    partial.unlink(missing_ok=True)
    raise SystemExit(f"Could not download {url} with the expected size and SHA-256")


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    target = Path(argv[1])
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    print(f"model {manifest['repo']} @ {manifest['revision']}", flush=True)

    for item in manifest["files"]:
        path = target / item["path"]
        if verify(path, item["size"], item["sha256"]) is None:
            print(f"  {item['path']}: present, sha256 ok", flush=True)
            continue
        print(f"  {item['path']}: downloading {item['size']} bytes", flush=True)
        download(url_for(manifest, item["path"]), path, item["size"], item["sha256"])
        print(f"  {item['path']}: sha256 ok", flush=True)

    # Lets the service report which model it runs.
    (target / "model-manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
