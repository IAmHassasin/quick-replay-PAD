#!/usr/bin/env bash
# Markdown release notes for the commits between the previous v* tag and HEAD.
# Used by .github/workflows/cd.yml. Requires full history and tags
# (actions/checkout with fetch-depth: 0 and fetch-tags: true).
#
# Usage: scripts/ci/generate-changelog.sh <new-tag> [output-file]
set -euo pipefail

NEW_TAG="${1:?usage: generate-changelog.sh <new-tag> [output-file]}"
OUT="${2:-/dev/stdout}"
REPO_URL="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-}"

PREV_TAG="$(git describe --tags --abbrev=0 --match 'v[0-9]*' HEAD 2>/dev/null || true)"
if [ -n "$PREV_TAG" ]; then
  RANGE="${PREV_TAG}..HEAD"
else
  RANGE="HEAD~50..HEAD"
  git rev-parse --verify -q HEAD~50 >/dev/null || RANGE="HEAD"
fi

features=""
fixes=""
others=""
prs=""

while IFS=$'\x1f' read -r subject body; do
  [ -z "$subject" ] && continue
  num="$(printf '%s' "$subject" | sed -nE 's/^Merge pull request #([0-9]+).*/\1/p')"
  [ -z "$num" ] && continue
  title="$(printf '%s' "$body" | head -n 1)"
  prs+="- ${title:-$subject} ([#${num}](${REPO_URL}/pull/${num}))"$'\n'
done < <(git log --merges --format='%s%x1f%b%x1e' "$RANGE" | tr '\036' '\n' | tr -s '\n')

while IFS=$'\x1f' read -r sha subject; do
  [ -z "$sha" ] && continue
  line="- ${subject} (${sha})"
  case "$subject" in
    feat*) features+="$line"$'\n' ;;
    fix*) fixes+="$line"$'\n' ;;
    *) others+="$line"$'\n' ;;
  esac
done < <(git log --no-merges --format='%h%x1f%s' "$RANGE")

{
  echo "## ${NEW_TAG}"
  echo
  if [ -n "$PREV_TAG" ]; then
    echo "Changes since ${PREV_TAG} ([compare](${REPO_URL}/compare/${PREV_TAG}...${NEW_TAG}))."
  else
    echo "First automated release."
  fi
  echo
  [ -n "$prs" ] && printf '### Pull requests\n\n%s\n' "$prs"
  [ -n "$features" ] && printf '### Features\n\n%s\n' "$features"
  [ -n "$fixes" ] && printf '### Bug fixes\n\n%s\n' "$fixes"
  [ -n "$others" ] && printf '### Other changes\n\n%s\n' "$others"
  if [ -z "${prs}${features}${fixes}${others}" ]; then
    echo "_No changes recorded._"
  fi
} > "$OUT"
