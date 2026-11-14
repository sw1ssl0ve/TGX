# TGX Performance Baseline

This document defines the measurement methodology. No quantitative baseline
numbers are currently measured in this repository: every `—` is explicitly
TBD / not measured and must not be presented as a benchmark result. A separate
physical-device smoke run observed approximately seven hours of stable TGX and
Telegram operation without crash, lag, or network failure, but it did not
collect RAM/CPU/FD measurements and is not a benchmark.

---

## Test environment

| Field | Value |
|---|---|
| Device model | — |
| Android version | — |
| TGX versionName | 1.1.0 |
| TGX versionCode | 2 |
| Measurement date | — |
| Network (primary) | — |
| ADB serial | — |

---

## A. Cold start latency

**Definition:** time from process start to local listener accepting the first
connection on 127.0.0.1:1443.

**How to measure:**

```bash
# Clear the app and logcat buffer, then force-stop and relaunch.
adb shell am force-stop io.github.tgx.android
adb logcat -c
adb shell am start -n io.github.tgx.android/.TgxSplashActivity
adb logcat -v time TGX-MTPROTO:I *:S | grep -E "started|Listening on"
```

Look for the logcat line:
```
TGX-MTPROTO: Listening on 127.0.0.1:1443
```

Subtract the process start timestamp (visible in `ActivityManager` logcat) from
the listener-ready timestamp.

| Run | Process start (ms) | Listener ready (ms) | Cold start (ms) |
|---|---|---|---|
| 1 | — | — | — |
| 2 | — | — | — |
| 3 | — | — | — |
| **Median** | | | **—** |

---

## B. First upstream WebSocket connected

**Definition:** time from listener ready to first upstream WSS `onConnected`
callback (pool or direct).

**How to measure:**

```bash
adb logcat -v time TGX-MTPROTO:I TGX-CF-WSS-Pool:I *:S | \
  grep -E "Listening on|WSS pool ready|CONNECTION START|WebSocket connection established"
```

| Configuration | Listener ready → upstream connected (ms) |
|---|---|
| Warm pool ON | — |
| Warm pool OFF | — |

---

## C. First Telegram data latency

**Definition:** time from Telegram opening a local connection to first relay
binary payload arriving from upstream.

**How to measure:** Open Telegram, trigger a chat load, observe:

```bash
adb logcat -v time TGX-MTPROTO:I *:S | grep -E "relay data|ROUTE RESPONSE SCORE"
```

| Configuration | First data latency (ms) |
|---|---|
| Warm pool ON (pool hit) | — |
| Warm pool ON (pool miss) | — |
| Warm pool OFF | — |

---

## D–F. Resource usage: warm pool ON / OFF / partial

**How to measure:**

```bash
# Memory (repeat every 30 s for 5 minutes)
adb shell dumpsys meminfo io.github.tgx.android | grep -E "TOTAL|Native Heap|Java Heap"

# Thread count
adb shell cat /proc/$(adb shell pidof io.github.tgx.android)/status | grep Threads

# Open file descriptors (sockets)
adb shell ls /proc/$(adb shell pidof io.github.tgx.android)/fd | wc -l

# CPU (5 samples, 1 s interval)
adb shell top -n 5 -d 1 -p $(adb shell pidof io.github.tgx.android)
```

### D. Warm pool ON (POOL_SIZE=4, DCs 1/2/4)

| Metric | Value |
|---|---|
| Startup time to listener ready | — ms |
| First upstream connected | — ms |
| PSS (steady state) | — MB |
| Java heap | — MB |
| Native heap | — MB |
| Thread count (steady) | — |
| Open FDs / sockets (steady) | — |
| CPU (idle, %) | — |
| Network traffic (idle, 1 h) | — KB |

### E. Warm pool OFF

Disable pool by setting `WSS_POOL_DCS = emptySet()` in a debug build, or by
measuring with `preventRefill` logic in tests.

| Metric | Value |
|---|---|
| Startup time to listener ready | — ms |
| First upstream connected | — ms |
| PSS (steady state) | — MB |
| Thread count (steady) | — |
| Open FDs / sockets (steady) | — |
| CPU (idle, %) | — |
| Network traffic (idle, 1 h) | — KB |

### F. Partially populated pool (pool filling, not yet at POOL_SIZE)

Measured during the first 30 seconds after cold start.

| Metric | Value |
|---|---|
| Thread count (peak) | — |
| Open FDs (peak) | — |
| CPU (peak, %) | — |

---

## G. Network switch

**Procedure:** Start TGX with Telegram open. Switch network interface while
observing Logcat and Telegram connectivity.

```bash
adb logcat -v time TGX-MTPROTO:I TGX-CF-WSS-Pool:I *:S | \
  grep -E "network|available|stopped|started|reconnect"
```

| Transition | Time to reconnect (s) | Data loss observed |
|---|---|---|
| Wi-Fi → LTE/5G | — | — |
| LTE/5G → Wi-Fi | — | — |
| Wi-Fi → Airplane → Wi-Fi | — | — |

---

## H. Idle state (TGX running, no Telegram traffic)

**Duration:** 30 minutes minimum.

| Metric | Value |
|---|---|
| PSS after 30 min idle | — MB |
| Thread count after 30 min idle | — |
| Open FDs after 30 min idle | — |
| CPU average (%) | — |
| Network traffic (30 min) | — KB |
| Stale socket closures observed | — |
| Unexpected Log.w/e entries | — |

---

## I. Long-running test (minimum 4 hours on physical device)

**Procedure:** Start TGX, leave Telegram open in background, collect periodic
snapshots every 30 minutes.

```bash
# Snapshot script (run on host, polling every 30 min):
for i in $(seq 1 8); do
  echo "=== $(date) ===" >> tgx_longrun.log
  adb shell dumpsys meminfo io.github.tgx.android | grep TOTAL >> tgx_longrun.log
  adb shell cat /proc/$(adb shell pidof io.github.tgx.android)/status \
    | grep -E "Threads|VmRSS" >> tgx_longrun.log
  adb shell ls /proc/$(adb shell pidof io.github.tgx.android)/fd \
    | wc -l >> tgx_longrun.log
  sleep 1800
done
```

| Time (h) | PSS (MB) | Threads | FDs | Notes |
|---|---|---|---|---|
| 0 | — | — | — | Baseline |
| 0.5 | — | — | — | |
| 1 | — | — | — | |
| 2 | — | — | — | |
| 4 | — | — | — | |

**Leak indicators to watch:**
- Thread count increasing monotonically → thread leak
- FD count increasing monotonically → socket/file descriptor leak
- PSS increasing >10 MB/h → memory leak
- Unexpected `TGX-MTPROTO W` or `E` logcat entries → production error path

---

## Warm pool benefit verdict

Fill in after completing D and E:

| Metric | Pool ON | Pool OFF | Delta |
|---|---|---|---|
| Cold start → first upstream (ms) | — | — | — |
| First Telegram data (ms) | — | — | — |
| Idle PSS (MB) | — | — | — |
| Idle threads | — | — | — |
| Idle network (KB/h) | — | — | — |

> **Decision:** Pool is worth keeping if first-upstream delta > 200 ms AND idle
> overhead < 5 MB PSS and < 5 extra threads. Otherwise reconsider POOL_SIZE.

---

## Battery impact

Use Android Studio Energy Profiler or `adb shell dumpsys batterystats` before
and after a 1-hour idle run:

```bash
adb shell dumpsys batterystats --reset
# ... wait 1 hour ...
adb shell dumpsys batterystats io.github.tgx.android > batterystats.txt
```

| Metric | Value |
|---|---|
| Wake locks held | — |
| CPU time (foreground) | — |
| CPU time (background) | — |
| Network bytes sent | — |
| Network bytes received | — |
| Estimated mAh | — |
