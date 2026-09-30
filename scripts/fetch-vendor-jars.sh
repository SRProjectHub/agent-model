#!/usr/bin/env bash
# Populates vendor/ with the Repast Simphony jars this project needs, extracted from
# the official v2.11.0 update-site release. See vendor/README.md for what's here and
# why. Idempotent/re-runnable. Requires network access.
set -euo pipefail

REPAST_VERSION="2.11.0"
RELEASE_TAG="v.2.11.0"
ASSET_NAME="repast.simphony.updatesite.${REPAST_VERSION}.zip"
DOWNLOAD_URL="https://github.com/Repast/repast.simphony/releases/download/${RELEASE_TAG}/${ASSET_NAME}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
VENDOR_DIR="${REPO_ROOT}/vendor"
SCRATCH_DIR="$(mktemp -d)"
trap 'rm -rf "${SCRATCH_DIR}"' EXIT

mkdir -p "${VENDOR_DIR}"

echo "Downloading ${ASSET_NAME} ..."
curl -fL --retry 3 -o "${SCRATCH_DIR}/updatesite.zip" "${DOWNLOAD_URL}"

echo "Extracting update site ..."
UPDATESITE_DIR="${SCRATCH_DIR}/extracted"
mkdir -p "${UPDATESITE_DIR}"
python3 - "$SCRATCH_DIR/updatesite.zip" "$UPDATESITE_DIR" <<'PYEOF'
import sys, zipfile
zip_path, out_dir = sys.argv[1], sys.argv[2]
zipfile.ZipFile(zip_path).extractall(out_dir)
PYEOF

PLUGINS_DIR="${UPDATESITE_DIR}/repast.simphony.updatesite/plugins"

declare -a RESULTS=()

fetch_plugin_jar() {
  local plugin_name="$1"       # e.g. repast.simphony.core
  local target_name="$2"       # e.g. repast.simphony.core-2.11.0.jar
  local src="${PLUGINS_DIR}/${plugin_name}_${REPAST_VERSION}.jar"
  if [[ -f "${src}" ]]; then
    # Repast's plugin jars are OSGi bundles whose actual .class files live under a
    # bin/ prefix inside the jar (per their own Bundle-ClassPath: "bin/,..."), which a
    # plain (non-OSGi) Java classpath does not understand — repackage into a normal
    # flat jar with that prefix stripped so it works with a plain -cp/classpath
    # reference. Also drops the bundle's code-signing entries (now invalid, since the
    # contents changed) and its nested lib/*.jar third-party deps (fetched separately
    # where actually needed, via fetch_nested_jar, to keep this jar lean).
    if python3 - "$src" "${VENDOR_DIR}/${target_name}" <<'PYEOF'
import sys, zipfile
src_path, out_path = sys.argv[1], sys.argv[2]
src = zipfile.ZipFile(src_path)
with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as out:
    for info in src.infolist():
        name = info.filename
        if name.startswith("bin/") and not name.endswith("/"):
            out.writestr(name[len("bin/"):], src.read(name))
        elif name == "META-INF/MANIFEST.MF":
            out.writestr(name, src.read(name))
        elif name.startswith("licenses/") and not name.endswith("/"):
            out.writestr(name, src.read(name))
PYEOF
    then
      RESULTS+=("OK   ${target_name}")
    else
      RESULTS+=("FAIL ${target_name} (repackaging failed)")
    fi
  else
    RESULTS+=("FAIL ${target_name} (source not found: ${src})")
  fi
}

# Extract a jar nested inside another plugin's bundled lib/ directory (Repast's OSGi
# bundles embed their third-party Bundle-ClassPath jars inside themselves, they are not
# separate top-level plugins in the update site).
fetch_nested_jar() {
  local containing_plugin="$1"  # e.g. repast.simphony.core
  local nested_path="$2"        # e.g. lib/colt-1.2.0-no_hep.jar
  local target_name="$3"        # e.g. colt-1.2.0-no_hep.jar
  local container="${PLUGINS_DIR}/${containing_plugin}_${REPAST_VERSION}.jar"
  if [[ -f "${container}" ]]; then
    if python3 - "$container" "$nested_path" "${VENDOR_DIR}/${target_name}" <<'PYEOF'
import sys, zipfile
container, nested_path, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
z = zipfile.ZipFile(container)
if nested_path not in z.namelist():
    sys.exit(1)
with z.open(nested_path) as src, open(out_path, "wb") as dst:
    dst.write(src.read())
PYEOF
    then
      RESULTS+=("OK   ${target_name}")
    else
      RESULTS+=("FAIL ${target_name} (nested entry not found: ${nested_path} in ${container})")
    fi
  else
    RESULTS+=("FAIL ${target_name} (containing plugin not found: ${container})")
  fi
}

fetch_plugin_jar "repast.simphony.core" "repast.simphony.core-${REPAST_VERSION}.jar"
fetch_plugin_jar "repast.simphony.dataLoader" "repast.simphony.dataLoader-${REPAST_VERSION}.jar"
fetch_nested_jar "repast.simphony.core" "lib/colt-1.2.0-no_hep.jar" "colt-1.2.0-no_hep.jar"
# repast.simphony.engine.schedule.Schedule touches the "indriya" units-of-measurement
# library (used by its getTickCountInTimeQuantity()-style API) at class-init time, so
# these are load-bearing at runtime even though core's own public scheduling API
# (ISchedule/ScheduleParameters/RunEnvironment) never surfaces them directly — found by
# actually running a test against the vendored jar, not from the manifest alone.
fetch_nested_jar "repast.simphony.core" "lib/indriya-2.0.2.jar" "indriya-2.0.2.jar"
fetch_nested_jar "repast.simphony.core" "lib/si-quantity-2.0.1.jar" "si-quantity-2.0.1.jar"
fetch_nested_jar "repast.simphony.core" "lib/si-units-2.0.1.jar" "si-units-2.0.1.jar"
fetch_nested_jar "repast.simphony.core" "lib/systems-common-2.0.2.jar" "systems-common-2.0.2.jar"
fetch_nested_jar "repast.simphony.core" "lib/unit-api-2.0.jar" "unit-api-2.0.jar"
fetch_nested_jar "repast.simphony.core" "lib/uom-lib-common-2.0.jar" "uom-lib-common-2.0.jar"
# RandomHelper's static initializer (repast.simphony.random.RandomHelper.<clinit>)
# needs simphony.util.messages.MessageCenter, which lives in saf.core.runtime.jar,
# itself nested inside repast.simphony.runtime (not repast.simphony.core) — the
# Require-Bundle: repast.simphony.runtime the core manifest declares turned out to be
# load-bearing after all for this one class, confirmed by actually running a test.
fetch_nested_jar "repast.simphony.runtime" "lib/saf.core.runtime.jar" "saf.core.runtime.jar"

echo
echo "Summary:"
for r in "${RESULTS[@]}"; do
  echo "  ${r}"
done

if printf '%s\n' "${RESULTS[@]}" | grep -q '^FAIL'; then
  echo
  echo "One or more jars failed to extract — see FAIL lines above." >&2
  exit 1
fi

echo
echo "vendor/ populated successfully."
