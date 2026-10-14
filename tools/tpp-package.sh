#!/usr/bin/env bash
# Builds the source archives for TAK.gov's Third Party Pipeline (TPP), which builds the plugin
# and signs it for release ATAK. One zip per ATAK version: a single root folder (its name
# becomes the APKs' name), the build set to that version (atakVersion in gradle.properties).
# Takes the working tree as it is, committed or not, without touching the git index. See docs/07.
#
#   tools/tpp-package.sh [ATAK version...]   (default: 5.6.0 5.8.0)
#   -> build/tpp/takconvo-atak56.zip, build/tpp/takconvo-atak58.zip
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
out="$root/build/tpp"
# the files as they are: with core.autocrlf, git archive would give gradlew Windows line
# endings, which break it on the pipeline's Linux
git() { command git -c core.autocrlf=false -c core.safecrlf=false "$@"; }
versions=("$@")
if [ ${#versions[@]} -eq 0 ]; then
    versions=(5.6.0 5.8.0)
fi
mkdir -p "$out"
index=$(mktemp)
props=$(mktemp)
trap 'rm -f "$index" "$props"' EXIT

# not needed to build, and they name internal hosts
left_out=(docs tools provisioning README.md template.local.properties .gitlab-ci.yml)

# no git in the archive: the version takdev would take from it. Changes to what the zip leaves
# out don't count, except to the user manual, which it keeps. The repository's own line endings
# setting: a file checked out again with Windows line endings is no change
revision=$(git -C "$root" rev-parse --short=8 HEAD)
changes=$(command git -C "$root" status --porcelain -- . "${left_out[@]/#/:(exclude)}")
changes+=$(command git -C "$root" status --porcelain -- docs/user_manual)
if [ -n "$changes" ]; then
    revision="$revision-wip"
    echo "warning: uncommitted changes; the version is $revision" >&2
fi
# the version code: the packaging time to 100 s, ending in the ATAK version (...56, ...58). The
# APKs for each ATAK version need their own: an MDM such as SOTI MobiControl refuses a second
# APK of the package with a version code it already has
packagedAt=$(( $(date +%s) / 100 * 100 ))

for version in "${versions[@]}"; do
    atak=$(echo "$version" | cut -d. -f1,2 | tr -d .)
    if [ ${#atak} -ne 2 ]; then
        echo "$version: the version code has two digits for the ATAK version" >&2
        exit 1
    fi
    name="takconvo-atak$atak"
    versionCode=$(( packagedAt + atak ))
    (
        export GIT_INDEX_FILE="$index"
        git -C "$root" read-tree HEAD
        # the working tree, without what .gitignore excludes (local.properties, build output)
        git -C "$root" add -A
        git -C "$root" rm -r -q --cached --ignore-unmatch "${left_out[@]}"
        # the pipeline resolves the takdev plugin and the SDK from its own repository, whose
        # artifacts aren't in the checksums: the build would refuse them
        git -C "$root" rm -q --cached --ignore-unmatch gradle/verification-metadata.xml
        # except the user manual: the pipeline builds it into the APK (gradle/typst.gradle)
        git -C "$root" add docs/user_manual
        # the pipeline runs ./gradlew; the repository comes from Windows
        git -C "$root" update-index --chmod=+x gradlew
        sed "s/^atakVersion=.*/atakVersion=$version/" "$root/gradle.properties" > "$props"
        printf '\n# written by tools/tpp-package.sh\ntakVersionName=%s\ntakStaticVersion=%s\n' \
            "$revision" "$versionCode" >> "$props"
        blob=$(git -C "$root" hash-object -w "$props")
        git -C "$root" update-index --cacheinfo "100644,$blob,gradle.properties"
        tree=$(git -C "$root" write-tree)
        git -C "$root" archive --format=zip --prefix="$name/" -o "$out/$name.zip" "$tree"
    )
    echo "$out/$name.zip (ATAK $version)"
done
