#!/usr/bin/env python3
"""Profiles Galva's startup CPU in the release-like `profiling` build of the demo app.

Runs the performance probe (app/src/perf) in fresh processes. Each run records an ART sampling
method trace of the whole process, from Application.onCreate to the probe's sample. The script pulls
the traces and reports thread CPU per run, charged to the deepest SDK (io.galva.*) frame on the
stack and split by the leaf method that spent it.

usage: profile.py [--runs N] [--modes sdk,baseline] [--out DIR] [--serial SERIAL] [--top N]
Started by scripts/profile.sh, which builds and passes the APK.
"""
import argparse, collections, os, re, struct, subprocess, sys, time

PKG = "io.galva.sample.profiling"
REMOTE = f"/sdcard/Android/data/{PKG}/files/galva_perf"
SDK = "io.galva."


def run_probes(adb, apk, runs, modes, out):
    def call(*args, check=False):
        r = subprocess.run(adb + list(args), capture_output=True, text=True)
        if check and r.returncode != 0:
            sys.exit(f"adb {' '.join(args)} failed: {r.stdout}{r.stderr}")
        return r.stdout.strip()

    os.makedirs(out, exist_ok=True)
    call("install", "-r", apk, check=True)
    call("shell", "rm", "-rf", REMOTE)
    traces = collections.defaultdict(list)
    for run in range(runs):
        for mode in modes:
            activity = "PerfSdkActivity" if mode == "sdk" else "PerfBaselineActivity"
            process = f"{PKG}:perf_{mode}"
            call("shell", "am", "force-stop", PKG)
            before = set(call("shell", "ls", REMOTE).split())
            call("shell", "am", "start", "-W", "-n", f"{PKG}/io.galva.sample.perf.{activity}")
            # Wait for the probe process to appear, then to exit: it samples 2 s after the first
            # frame, stops the trace and kills itself.
            deadline = time.time() + 30
            while time.time() < deadline and not call("shell", "pidof", process):
                time.sleep(0.5)
            deadline = time.time() + 90
            while time.time() < deadline and call("shell", "pidof", process):
                time.sleep(1)
            time.sleep(1)
            for name in sorted(set(call("shell", "ls", REMOTE).split()) - before):
                if name.endswith(".trace"):
                    local = os.path.join(out, name)
                    subprocess.run(adb + ["pull", f"{REMOTE}/{name}", local], capture_output=True)
                    if os.path.getsize(local) > 0:
                        traces[mode].append(local)
            print(f"{mode} run {run + 1}/{runs}: {len(traces[mode])} trace(s)", file=sys.stderr)
    return traces


def analyse(paths):
    """Thread CPU (µs) by thread, by deepest SDK frame, and by (SDK frame, leaf)."""
    by_thread, by_frame, by_leaf = collections.Counter(), collections.Counter(), collections.Counter()
    for path in paths:
        data = open(path, "rb").read()
        end = data.index(b"*end\n")
        header = data[:end].decode("utf-8", "replace").split("\n")
        t0, m0 = header.index("*threads"), header.index("*methods")
        threads = dict(l.split("\t", 1) for l in header[t0 + 1:m0] if "\t" in l)
        methods = {}
        for line in header[m0 + 1:]:
            parts = line.split("\t")
            if len(parts) >= 3:
                methods[int(parts[0], 16)] = f"{parts[1]}.{parts[2]}"
        body = data[end + 5:]
        _, _, offset, _ = struct.unpack("<IHHQ", body[:16])
        size = struct.unpack("<H", body[16:18])[0]
        stacks, clock = collections.defaultdict(list), {}
        for pos in range(offset, len(body) - size + 1, size):
            tid, value, tclock, _ = struct.unpack("<HIII", body[pos:pos + 14])
            stack = stacks[tid]
            delta = tclock - clock.get(tid, tclock)
            clock[tid] = tclock
            if delta > 0:
                by_thread[re.sub(r"[-\s:]?\d+$", "", threads.get(str(tid), str(tid)))] += delta
                names = [methods.get(m, hex(m)) for m in stack]
                sdk = [n for n in names if n.startswith(SDK)]
                if sdk:
                    by_frame[sdk[-1]] += delta
                    by_leaf[(sdk[-1], names[-1])] += delta
            if value & 3 == 0:
                stack.append(value & ~3)
            else:
                while stack and stack.pop() != value & ~3:
                    pass
    return by_thread, by_frame, by_leaf


def report(mode, paths, top):
    by_thread, by_frame, by_leaf = analyse(paths)
    per_run = lambda us: us / 1000 / len(paths)
    print(f"\n# {mode}: {len(paths)} run(s), thread CPU in ms per run (sampling: indicative, not exact)")
    print("\n## By thread")
    for name, us in by_thread.most_common(8):
        print(f"{per_run(us):8.0f}  {name}")
    if not by_frame:
        return
    print(f"\n## Under an SDK frame: {per_run(sum(by_frame.values())):.0f} ms, by deepest SDK frame")
    for name, us in by_frame.most_common(top):
        print(f"{per_run(us):8.0f}  {name}")
    print("\n## Deepest SDK frame -> leaf")
    for (frame, leaf), us in by_leaf.most_common(top):
        print(f"{per_run(us):8.0f}  {frame} -> {leaf}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--modes", default="sdk,baseline")
    parser.add_argument("--out", default="app/build/reports/profile")
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--top", type=int, default=25)
    args = parser.parse_args()
    adb = ["adb"] + (["-s", args.serial] if args.serial else [])
    traces = run_probes(adb, args.apk, args.runs, args.modes.split(","), args.out)
    for mode, paths in traces.items():
        if paths:
            report(mode, paths, args.top)
    print(f"\nTraces: {args.out} (open in Android Studio's profiler for call trees)")


if __name__ == "__main__":
    main()
