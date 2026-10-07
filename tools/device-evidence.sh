#!/usr/bin/env bash
# Evidence helper for docs/testing/DEVICE_TESTING_EVIDENCE.md. Run from the repository root
# with adb and exactly one phone connected over USB debugging.
set -euo pipefail

PKG=au.edu.unimelb.floraguide
if [ -z "${ADB:-}" ]; then
  if command -v adb >/dev/null 2>&1; then ADB=adb
  elif [ -x "$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe" ]; then ADB="$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe"
  else ADB="$HOME/Library/Android/sdk/platform-tools/adb"
  fi
fi

prop() { "$ADB" shell getprop "$1" | tr -d '\r'; }

outdir() {
  local dir
  dir="docs/evidence/device/$(prop ro.product.model | tr ' /' '__')"
  mkdir -p "$dir"
  echo "$dir"
}

case "${1:-help}" in
  info)
    dir=$(outdir)
    sensors=$("$ADB" shell dumpsys sensorservice | tr -d '\r')
    {
      echo "model: $(prop ro.product.manufacturer) $(prop ro.product.model)"
      echo "android: $(prop ro.build.version.release) (API $(prop ro.build.version.sdk))"
      echo "commit: $(git rev-parse --short HEAD)$(git diff --quiet HEAD -- || echo ' + uncommitted changes')"
      echo "app: $("$ADB" shell dumpsys package "$PKG" | tr -d '\r' | grep -m1 versionName | sed 's/^ *//' || echo 'not installed')"
      echo "sensors:"
      for type in accelerometer gyroscope light magnetic_field; do
        if grep -q "android\.sensor\.$type\b" <<<"$sensors"; then echo "  $type: yes"; else echo "  $type: no"; fi
      done
    } | tee "$dir/device-info.txt"
    ;;
  install) ./gradlew installDebug ;;
  fresh) "$ADB" shell pm clear "$PKG" ;;
  shot) "$ADB" exec-out screencap -p > "$(outdir)/${2:?usage: shot NAME}.png" && echo "saved" ;;
  revoke|grant)
    "$ADB" shell pm "$1" "$PKG" "android.permission.${2:?usage: $1 CAMERA|ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION}" ;;
  location)
    enabled=$([ "${2:?usage: location on|off}" = on ] && echo true || echo false)
    "$ADB" shell cmd location set-location-enabled "$enabled" ||
      echo "This Android version rejected the command; toggle location in Quick Settings." ;;
  airplane)
    mode=$([ "${2:?usage: airplane on|off}" = on ] && echo enable || echo disable)
    "$ADB" shell cmd connectivity airplane-mode "$mode" ||
      echo "This Android version rejected the command; toggle airplane mode in Quick Settings." ;;
  log-start)
    # A larger buffer keeps 30 s of stability-gate samples from being overwritten.
    "$ADB" logcat -G 4M >/dev/null 2>&1 || true
    "$ADB" logcat -c && echo "Logs cleared." ;;
  log-save) "$ADB" logcat -d -s FloraGuide-Location:I > "$(outdir)/${2:?usage: log-save NAME}.log" && echo "saved" ;;
  motion-save) "$ADB" logcat -d -s FloraGuide-Motion:I > "$(outdir)/${2:?usage: motion-save NAME}.log" && echo "saved" ;;
  *)
    echo "usage: tools/device-evidence.sh COMMAND"
    echo "commands: info, install, fresh, shot NAME, revoke|grant PERMISSION, location on|off, airplane on|off, log-start, log-save NAME, motion-save NAME"
    ;;
esac
