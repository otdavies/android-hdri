#!/usr/bin/env python3
"""Reuse a retained APK after CI-only fixes; refuse reuse after Android changes."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def output(key, value):
    with open(os.environ["GITHUB_OUTPUT"], "a") as file:
        file.write(f"{key}={value}\n")


def api(path):
    return json.loads(subprocess.check_output(["gh", "api", f"repos/{os.environ['GITHUB_REPOSITORY']}/{path}"], text=True))


def code_changed(files):
    # Both app code and test code must still match the retained, paired APKs.
    protected = ("app/", "gradle/", "gradlew", "gradlew.bat", "build.gradle.kts",
                 "settings.gradle.kts", "gradle.properties", "scripts/prepare-model.sh",
                 "scripts/prepare-test-video.sh")
    return len(files) >= 300 or any(
        item.get(key, "").startswith(protected)
        for item in files for key in ("filename", "previous_filename")
    )


def main():
    if sys.argv[1] == "select":
        run_id = os.environ.get("REQUESTED_RUN_ID", "").strip()
        marker = re.search(r"\[reuse:(\d+)\]", os.environ.get("COMMIT_MESSAGE", ""))
        if not run_id and marker:
            run_id = marker.group(1)
        if run_id and not re.fullmatch(r"[1-9]\d*", run_id):
            raise ValueError("Candidate run ID must be a positive number")
        output("run_id", run_id)
        # Reproducing an unchanged app for test-only edits should not invalidate
        # R8's cache solely because BuildConfig.VERSION_CODE changed.
        code = os.environ.get("REQUESTED_VERSION_CODE", "").strip()
        code_marker = re.search(r"\[apk-code:(\d+)\]", os.environ.get("COMMIT_MESSAGE", ""))
        if not code and code_marker:
            code = code_marker.group(1)
        if code:
            if run_id or not code.isdigit() or not 1 <= int(code) <= 2100000000:
                raise ValueError("Use a valid Android version code for a new/reproduced build, not artifact reuse")
            with open(os.environ["GITHUB_ENV"], "a") as file:
                file.write(f"HDRI_VERSION_CODE={int(code)}\n")
            print(f"Reproducing Android version code {code}")
        print(f"Reusing paired APKs from run {run_id}" if run_id else "Building a new paired candidate")
        return

    source_file = Path("candidate/SOURCE_SHA.txt")
    run_id = os.environ.get("REUSED_RUN_ID", "")
    if run_id:
        run = api(f"actions/runs/{run_id}")
        if run["head_branch"] != "main":
            raise ValueError("Only candidates built from main may be reused")
        source = source_file.read_text().strip() if source_file.exists() else run["head_sha"]
    else:
        source = os.environ["GITHUB_SHA"]
    if not re.fullmatch(r"[a-f0-9]{40}", source):
        raise ValueError("Invalid candidate source commit")
    if run_id and source != os.environ["GITHUB_SHA"]:
        comparison = api(f"compare/{source}...{os.environ['GITHUB_SHA']}")
        if comparison["status"] not in ("ahead", "identical") or code_changed(comparison.get("files", [])):
            raise ValueError("Android code changed since this candidate; build a new APK instead of reusing it")
    source_file.write_text(source + "\n")
    output("source_sha", source)
    print(f"Verified candidate source: {source}")


if __name__ == "__main__":
    main()

