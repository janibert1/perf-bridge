#!/usr/bin/env python3
"""
Reads Intel RAPL package power every 100ms and writes watts to
/home/jan/minecraft/power_w so the PerfBridge plugin (inside the
Pterodactyl container) can read it at /home/container/power_w.
"""
import time, os, sys

RAPL = "/sys/class/powercap/intel-rapl:0/energy_uj"
OUT  = "/home/jan/minecraft/power_w"
TMP  = OUT + ".tmp"
INTERVAL = 0.1

def read_uj():
    with open(RAPL) as f:
        return int(f.read().strip())

prev_uj = read_uj()
prev_t  = time.monotonic()

while True:
    time.sleep(INTERVAL)
    now = time.monotonic()
    try:
        uj = read_uj()
    except Exception as e:
        sys.stderr.write(f"RAPL read error: {e}\n")
        continue
    dt    = now - prev_t
    delta = uj - prev_uj
    if delta < 0:
        delta += 2**32  # counter wrap (~262 kJ max)
    watts = (delta / 1e6) / dt if dt > 0 else 0.0
    try:
        with open(TMP, 'w') as f:
            f.write(f"{watts:.2f}\n")
        os.rename(TMP, OUT)
    except Exception as e:
        sys.stderr.write(f"Write error: {e}\n")
    prev_uj = uj
    prev_t  = now
