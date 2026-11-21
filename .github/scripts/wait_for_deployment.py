"""Wait until a Railway deployment started by `railway up` is live.

`railway up --ci` returns once the build logs end, before the new deployment has
started, so a release could move on while the old deployment still serves the
site. This script finds the deployment that is new since a listing taken before
the upload and waits until Railway reports it SUCCESS. Any other final status
fails the job.

Usage:
    python wait_for_deployment.py <before.json> --service NAME --environment NAME
        --project ID [--timeout SECONDS]

<before.json> is the output of `railway deployment list --json` taken before
`railway up`.
"""

import argparse
import json
import shutil
import subprocess
import sys
import time

FINAL_FAILURES = {"FAILED", "CRASHED", "REMOVED", "SKIPPED"}


def list_deployments(args: argparse.Namespace) -> list[dict]:
    output = subprocess.run(
        [shutil.which("railway") or "railway", "deployment", "list", "--json", "--limit", "20",
         "--project", args.project, "--service", args.service, "--environment", args.environment],
        check=True, capture_output=True, text=True,
    ).stdout
    return json.loads(output)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("before")
    parser.add_argument("--service", required=True)
    parser.add_argument("--environment", required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--timeout", type=int, default=900)
    args = parser.parse_args()

    with open(args.before, encoding="utf-8") as file:
        known = {deployment["id"] for deployment in json.load(file)}

    deadline = time.monotonic() + args.timeout
    last_status = None
    while time.monotonic() < deadline:
        new = [d for d in list_deployments(args) if d["id"] not in known]
        if new:
            deployment = max(new, key=lambda d: d["createdAt"])
            status = deployment["status"]
            if status != last_status:
                print(f"{args.service}: deployment {deployment['id']} is {status}", flush=True)
                last_status = status
            if status == "SUCCESS":
                return 0
            if status in FINAL_FAILURES:
                print(f"::error::{args.service} deployment {deployment['id']} ended as {status}.")
                return 1
        time.sleep(10)

    print(f"::error::{args.service}: no new deployment went live within {args.timeout} s.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
