#!/usr/bin/env python3
"""Post the next queued line from posts.txt to X, then remove it from the queue."""
import os
import sys
from pathlib import Path

from requests_oauthlib import OAuth1Session

POSTS_FILE = Path(__file__).parent / "posts.txt"


def load_credentials():
    required = [
        "X_CONSUMER_KEY",
        "X_CONSUMER_SECRET",
        "X_ACCESS_TOKEN",
        "X_ACCESS_TOKEN_SECRET",
    ]
    missing = [name for name in required if not os.environ.get(name)]
    if missing:
        sys.exit(f"Missing required secrets: {', '.join(missing)}")
    return {name: os.environ[name] for name in required}


def next_post(lines):
    for i, line in enumerate(lines):
        if line.strip():
            return i, line.strip()
    return None, None


def main():
    if not POSTS_FILE.exists():
        sys.exit(f"{POSTS_FILE} not found")

    lines = POSTS_FILE.read_text(encoding="utf-8").splitlines()
    index, text = next_post(lines)
    if text is None:
        print("No queued posts left in posts.txt")
        return

    creds = load_credentials()
    session = OAuth1Session(
        creds["X_CONSUMER_KEY"],
        client_secret=creds["X_CONSUMER_SECRET"],
        resource_owner_key=creds["X_ACCESS_TOKEN"],
        resource_owner_secret=creds["X_ACCESS_TOKEN_SECRET"],
    )
    response = session.post("https://api.x.com/2/tweets", json={"text": text})
    if response.status_code != 201:
        sys.exit(f"Post failed ({response.status_code}): {response.text}")

    print(f"Posted: {text}")
    remaining = lines[:index] + lines[index + 1 :]
    POSTS_FILE.write_text(
        "\n".join(remaining) + ("\n" if remaining else ""), encoding="utf-8"
    )


if __name__ == "__main__":
    main()
