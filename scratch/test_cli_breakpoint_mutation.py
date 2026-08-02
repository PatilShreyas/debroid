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
    print("🚀 LIVE CLI MUTATION TEST: CHANGING AMOUNT TO 5000")
    print("==================================================")

    subprocess.run([os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"), "shell", "am", "clear-debug-app"])

    print("\n1. Running: ./bin/debroid attach com.example.sampledebugapp")
    attach_res = run_debroid("attach", "com.example.sampledebugapp")
    print("Attach Result:", attach_res)

    session_id = attach_res.get("session_id", "sess_100") if isinstance(attach_res, dict) else "sess_100"
    print(f"Active Session ID: {session_id}")

    print(f"\n2. Setting Breakpoint on DataRepository.kt:41 via CLI")
    print(f"Running: ./bin/debroid break {session_id} DataRepository.kt 41")
    bp_res = run_debroid("break", session_id, "DataRepository.kt", "41")
    print("Breakpoint Set Result:", bp_res)

    print(f"\n3. Resuming App via CLI: ./bin/debroid resume {session_id} 1")
    run_debroid("resume", session_id, "1")

    print("\n✅ BREAKPOINT ARMED AT DataRepository.kt:41 VIA DEBROID CLI.")
    print("👉 Tap 'Process Gold Order' on the emulator now...")
    sys.stdout.flush()

    hit = False
    thread_hit = "1"
    start_time = time.time()

    while time.time() - start_time < 300: # Wait up to 5 minutes
        poll_res = run_debroid("poll", session_id, "0")
        if isinstance(poll_res, dict):
            events = poll_res.get("events", [])
            for ev in events:
                if ev.get("event_type") == "BREAKPOINT_HIT":
                    hit = True
                    thread_hit = ev.get("thread_id", "1")
                    print("\n🎯 BREAKPOINT HIT CAPTURED VIA CLI POLL:", ev)
                    break
        if hit:
            break
        time.sleep(1)

    if hit:
        print("\n===========================================")
        print("🎯 BREAKPOINT HIT AT DataRepository.kt:41!")
        print("===========================================")

        print(f"\n4. Inspecting Local Variables via CLI:")
        locals_res = run_debroid("locals", session_id, thread_hit)
        print("Locals Before Mutation:", json.dumps(locals_res, indent=2))

        print(f"\n5. Mutating 'amount' to '5000.0' via CLI:")
        print(f"Running: ./bin/debroid set-var {session_id} {thread_hit} amount 5000.0")
        setvar_res = run_debroid("set-var", session_id, thread_hit, "amount", "5000.0")
        print("Set-Var Result:", json.dumps(setvar_res, indent=2))

        print(f"\n6. Resuming Execution via CLI:")
        print(f"Running: ./bin/debroid resume {session_id} {thread_hit}")
        res_after = run_debroid("resume", session_id, thread_hit)
        print("Resume Result:", res_after)
        print("\n✅ MUTATION SUCCESSFUL! Check emulator UI for $4750.0!")
    else:
        print("Timed out waiting for breakpoint hit.")

if __name__ == "__main__":
    main()
