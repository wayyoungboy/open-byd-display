#!/bin/sh
# Run manually only after an authorized QNX shell is established. Does not run
# our ELF, change USB, touch partitions, modify SLM or copy any runtime library.
# Save stdout on the operator computer; review locally before sharing.
set -u
printf '%s\n' 'OpenBYDDisplay QNX read-only baseline v1' '--- kernel ---'
uname -s -r -m
printf '%s\n' '--- identity (not a verified hardware profile) ---'
id
printf '%s\n' '--- filesystem overview ---'
df
printf '%s\n' '--- runtime SHA256 (unavailable remains unknown) ---'
for file in /proc/boot/libc.so.3 /usr/lib/ldqnx.so.2 /base/usr/lib/libscreen.so.1 /base/usr/lib/libmmrndclient.so.1 /base/usr/lib/libstrm.so.1; do
    if [ -f "$file" ]; then
        if command -v sha256sum >/dev/null 2>&1; then
            sha256sum "$file"
        elif command -v openssl >/dev/null 2>&1; then
            openssl dgst -sha256 "$file"
        else
            printf 'SHA256_UNAVAILABLE %s\n' "$file"
        fi
    else
        printf 'FILE_UNAVAILABLE %s\n' "$file"
    fi
done
printf '%s\n' '--- network (no changes) ---'
ifconfig -a
printf '%s\n' 'END_READ_ONLY_BASELINE; hardware_verified=false; firmware_flashable=false'
