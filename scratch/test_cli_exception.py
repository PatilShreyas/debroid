import subprocess
import json
import time
import sys
import os

def run_debroid(*args):
    cmd = ["./bin/debroid"] + list(args)
    res = subprocess.run(cmd, capture_output=True, text=True)
    out = res.stdout.strip()
    if not out:
        return None
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("{") and line.endswith("}"):
            try:
                return json.loads(line)
            except Exception:
                pass
    return out

def main():
    print("==================================================")
    print("💥 TESTING LIVE CATCH-EXCEPTION VIA DEBROID CLI")
    print("==================================================")

    subprocess.run([os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"), "shell", "am", "clear-debug-app"])

    print("\n1. Running: ./bin/debroid attach com.example.sampledebugapp")
    attach_res = run_debroid("attach", "com.example.sampledebugapp")
    print("Attach Result:", attach_res)

    session_id = attach_res.get("session_id", "sess_100") if isinstance(attach_res, dict) else "sess_100"
    print(f"Active Session ID: {session_id}")

    print(f"\n2. Setting Exception Breakpoint via CLI")
    print(f"Running: ./bin/debroid catch-exception {session_id} java.lang.IllegalArgumentException")
    ex_res = run_debroid("catch-exception", session_id, "java.lang.IllegalArgumentException")
    print("Exception Breakpoint Result:", ex_res)

    print(f"\n3. Resuming App via CLI: ./bin/debroid resume {session_id} 1")
    run_debroid("resume", session_id, "1")

    print("\n✅ EXCEPTION TRAP ARMED VIA DEBROID CLI.")
    print("👉 Tap 'Trigger Exception Order' on the emulator now...")
    sys.stdout.flush()

    hit = False
    thread_hit = "1"
    start_time = time.time()

    while time.time() - start_time < 300: # Wait up to 5 minutes
        poll_res = run_debroid("poll", session_id, "0")
        if isinstance(poll_res, dict):
            events = poll_res.get("events", [])
            for ev in events:
                if ev.get("event_type") == "EXCEPTION_HIT":
                    hit = True
                    thread_hit = ev.get("thread_id", "1")
                    print("\n🎯 EXCEPTION TRAPPED LIVE IN ART VIA DEBROID CLI!", ev)
                    break
        if hit:
            break
        time.sleep(1)

    if hit:
        print("\n===========================================")
        print("🎯 UNCAUGHT EXCEPTION TRAPPED BEFORE APP CRASH!")
        print("===========================================")

        print(f"\n4. Inspecting Local Variables at Exception Origin via CLI:")
        locals_res = run_debroid("locals", session_id, thread_hit)
        print("Locals at Crash Origin:", json.dumps(locals_res, indent=2))

        print(f"\n5. Resuming Execution via CLI:")
        run_debroid("resume", session_id, thread_hit)
        print("✅ Execution resumed!")
    else:
        print("Timed out waiting for exception hit.")

if __name__ == "__main__":
    main()
