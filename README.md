# PerfBridge

A Paper plugin that logs per-tick server performance data to a CSV file. Each tick records MSPT, CPU temperature, CPU frequency, and power draw.

Built because the standard `/mspt` command only gives you rolling averages — PerfBridge gives you the raw tick-by-tick data so you can actually see what's causing lag spikes.

## Features

- Per-tick CSV log: `tick, timestamp_ms, mspt, cpu_temp_c, cpu_freq_mhz, cpu_power_w`
- In-game command to start/stop recording with optional duration
- Live rolling JSON summary at `/tmp/mc_perf.json` (useful for terminal monitors)
- Cross-platform hardware stats via [OSHI](https://github.com/oshi/oshi) — works on Linux, macOS, and Windows
- Power draw via OS power supply interface (whole-system) or a custom sidecar file
- Zero overhead when not recording

## Requirements

- Paper 26.1.2+ (or any Paper build that includes `ServerTickEndEvent`)
- Java 21+

## Installation

Drop `PerfBridge.jar` into your `plugins/` folder and restart.

## Usage

```
/perfmon start [seconds]   — start recording; auto-stops after N seconds if given
/perfmon stop              — stop recording early
```

The CSV is saved to the `output-dir` defined in `config.yml` (default `/tmp`) with a timestamped filename like `mc_perf_2026-06-21_14-30-00.csv`.

Only players with the `perfbridge.use` permission (op by default) can run the command.

## Configuration

`plugins/PerfBridge/config.yml`:

```yaml
# Directory where CSV recordings are saved
output-dir: /tmp

# Path to a file containing the current CPU package power draw in watts (one float per line).
# Use this when running inside Docker (e.g. Pterodactyl) where RAPL is not accessible.
# Leave empty to let OSHI read power automatically via the OS power supply interface.
power-file: ""
```

## Power logging

Power draw is read via OSHI's power source interface by default — this gives whole-system draw and works on macOS, Windows, and bare-metal Linux without any extra setup.

If you're running inside Docker (e.g. Pterodactyl), OSHI can't reach the host power subsystem. In that case, set `power-file` to a path the container can read and write a float (watts) to that file from the host — any mechanism works (a small Python/shell script reading Intel RAPL, `powerstat`, etc.).

## CSV format

| Column | Description |
|--------|-------------|
| `tick` | Tick number within the recording session |
| `timestamp_ms` | Unix timestamp in milliseconds |
| `mspt` | Milliseconds per tick (how long this tick took) |
| `cpu_temp_c` | CPU package temperature in °C |
| `cpu_freq_mhz` | Average CPU frequency across all cores in MHz |
| `cpu_power_w` | System power draw in watts (`-1` if unavailable) |

A healthy server running at 20 TPS will show `mspt` values consistently below 50. Spikes above 50 mean the server missed a tick. Values above 100+ indicate serious lag.

## Building from source

```bash
git clone https://github.com/janibert1/perf-bridge.git
cd perf-bridge
mvn package
```

The shaded jar will be at `target/perf-bridge-1.2.jar`.

## Live JSON output

While the server is running, PerfBridge writes a rolling summary to `/tmp/mc_perf.json` every second (even when not recording):

```json
{
  "5s":  { "avg": 12.4, "min": 8.1, "max": 18.7, "tps": 20.0 },
  "10s": { "avg": 12.1, "min": 7.9, "max": 21.3, "tps": 19.8 },
  "1m":  { "avg": 11.8, "min": 7.2, "max": 48.2, "tps": 19.9 },
  "ts":  1750000000000
}
```

You can use this to build a terminal dashboard or feed it into monitoring tools without touching RCON.
