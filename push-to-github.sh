#!/usr/bin/env bash
#
# Pushes this repository to a GitHub repository you have already created.
#
#   ./push-to-github.sh <your-github-username> <repo-name>
#
# The repository must exist and be empty (no README, no .gitignore). When git asks for a
# password, paste a Personal Access Token, not your account password:
#   github.com -> Settings -> Developer settings -> Personal access tokens -> Tokens (classic)
#   -> Generate new token, tick "repo", copy it.
#
# To stop git asking every time, store the token in the macOS keychain once:
#   git config --global credential.helper osxkeychain

set -euo pipefail

owner="${1:-}"
repo="${2:-}"

if [ -z "$owner" ] || [ -z "$repo" ]; then
	echo "usage: ./push-to-github.sh <your-github-username> <repo-name>" >&2
	exit 1
fi

if [ ! -d .git ]; then
	echo "run this from the repository root" >&2
	exit 1
fi

url="https://github.com/${owner}/${repo}.git"
echo "pushing main to ${url}"

git remote remove origin 2>/dev/null || true
git remote add origin "$url"
git push -u origin main

echo
echo "done. clone it on any machine with:"
echo "  git clone ${url}"
