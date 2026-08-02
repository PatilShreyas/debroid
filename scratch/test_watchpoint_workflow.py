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
        print(f"Error executing {' '.join(cmd)}:\n{res.stderr}", file=sys.stderr)
        return None
    try:
        # Extract JSON object from output
        start_idx = out.find("{")
        end_idx = out.rfind("}")
        if start_idx != -1 and end_idx != -1:
            json_str = out[start_idx:end_idx+1]
            return json.loads(json_str)
    except Exception:
        pass
    return out

def main():
    print("==================================================")
    print("👁️ TESTING FIELD WATCHPOINT VERIFICATION (DEBROID)")
    print("==================================================")

    subprocess.run([os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"), "shell", "am", "clear-debug-app"])

    print("\n1. Running: ./bin/debroid attach com.example.sampledebugapp")
    attach_res = run_debroid("attach", "com.example.sampledebugapp")
    print("Result:", attach_res)

    session_id = attach_res.get("session_id", "sess_100") if isinstance(attach_res, dict) else "sess_100"
    print(f"Using Session ID: {session_id}")

    print(f"\n2. Setting Field Watchpoint on DefaultDataRepository.totalOrdersProcessed")
    print(f"Running: ./bin/debroid watch {session_id} com.example.sampledebugapp.data.DefaultDataRepository totalOrdersProcessed")
    wp_res = run_debroid("watch", session_id, "com.example.sampledebugapp.data.DefaultDataRepository", "totalOrdersProcessed")
    print("Watchpoint Set Result:", wp_res)

    print(f"\n3. Running: ./bin/debroid resume {session_id} 1")
    run_debroid("resume", session_id, "1")

    print("\n✅ WATCHPOINT ARMED FOR totalOrdersProcessed MODIFICATION.")
    print("👉 Tap 'Calculate Regular Order' on the emulator now...")
    sys.stdout.flush()

    hit = False
    thread_hit = "1"
    start_time = time.time()

    while time.time() - start_time < 300: # Wait up to 5 minutes
        poll_res = run_debroid("poll", session_id, "0")
        if isinstance(poll_res, dict):
            events = poll_res.get("events", [])
            for ev in events:
                if "WATCHPOINT" in ev.get("event_type", ""):
                    hit = True
                    thread_hit = ev.get("thread_id", "1")
                    print("\n🎯 WATCHPOINT HIT EVENT CAPTURED:", ev)
                    break
        if hit:
            break
        time.sleep(1)

    if hit:
        print("\n===========================================")
        print("🎯 FIELD WATCHPOINT TRIGGERED LIVE IN ART!")
        print("===========================================")

        print(f"\n4. Inspecting Local Variables at Watchpoint Hit:")
        locals_res = run_debroid("locals", session_id, thread_hit)
        print("Locals:", json.dumps(locals_res, indent=2))

        print(f"\n5. Resuming Execution via CLI:")
        run_debroid("resume", session_id, thread_hit)
        print("✅ Execution resumed cleanly!")
    else:
        print("Timed out waiting for watchpoint hit.")

if __name__ == "__main__":
    main()
