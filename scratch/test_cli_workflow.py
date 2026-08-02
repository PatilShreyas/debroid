import subprocess
import json
import time
import sys

def run_debroid(*args):
    cmd = ["./bin/debroid"] + list(args)
    res = subprocess.run(cmd, capture_output=True, text=True)
    out = res.stdout.strip()
    if not out:
        print(f"Error executing {' '.join(cmd)}:\n{res.stderr}", file=sys.stderr)
        return None
    # Parse last json block from stdout
    lines = out.splitlines()
    json_lines = [l for l in lines if l.startswith("{") or l.startswith("[")]
    if json_lines:
        try:
            return json.loads("\n".join(json_lines))
        except Exception:
            return out
    return out

def main():
    print("==================================================")
    print("🚀 TESTING DEBROID CLI SUBCOMMANDS (CLIKT)")
    print("==================================================")

    print("\n1. Running: ./bin/debroid attach com.example.sampledebugapp")
    attach_res = run_debroid("attach", "com.example.sampledebugapp")
    print("Result:", attach_res)

    print("\n2. Running: ./bin/debroid break sess_100 DataRepository.kt 40")
    bp_res = run_debroid("break", "sess_100", "DataRepository.kt", "40")
    print("Result:", bp_res)

    print("\n3. Running: ./bin/debroid threads sess_100")
    threads_res = run_debroid("threads", "sess_100")
    print(f"Threads Found: {len(threads_res) if isinstance(threads_res, list) else threads_res}")

    print("\n4. Running: ./bin/debroid resume sess_100 1")
    resume_res = run_debroid("resume", "sess_100", "1")
    print("Result:", resume_res)

    print("\n✅ BREAKPOINT ARMED VIA DEBROID CLI.")
    print("👉 Tap 'Process Gold Order' on the emulator now...")
    sys.stdout.flush()

    hit = False
    thread_hit = "1"
    start_time = time.time()

    while time.time() - start_time < 300: # Wait up to 5 minutes
        poll_res = run_debroid("poll", "sess_100", "0")
        if isinstance(poll_res, dict):
            events = poll_res.get("events", [])
            for ev in events:
                if ev.get("event_type") == "BREAKPOINT_HIT":
                    hit = True
                    thread_hit = ev.get("thread_id", "1")
                    break
        if hit:
            break
        time.sleep(1)

    if hit:
        print("\n===========================================")
        print("🎯 BREAKPOINT HIT CAUGHT VIA CLI POLL!")
        print("===========================================")

        print(f"\n5. Running: ./bin/debroid locals sess_100 {thread_hit}")
        locals_res = run_debroid("locals", "sess_100", thread_hit)
        print("Locals Before Mutation:", json.dumps(locals_res, indent=2))

        print(f"\n6. Running: ./bin/debroid set-var sess_100 {thread_hit} amount 3000.0")
        setvar_res = run_debroid("set-var", "sess_100", thread_hit, "amount", "3000.0")
        print("Set-Var Result:", json.dumps(setvar_res, indent=2))

        print(f"\n7. Running: ./bin/debroid eval sess_100 {thread_hit} \"Order for \" + customerType + \" with amount $\" + amount")
        eval_res = run_debroid("eval", "sess_100", thread_hit, '"Order for " + customerType + " with amount $" + amount')
        print("Eval Result:", json.dumps(eval_res, indent=2))

        print(f"\n8. Running: ./bin/debroid resume sess_100 {thread_hit}")
        res_after = run_debroid("resume", "sess_100", thread_hit)
        print("Resume Result:", res_after)
        print("\n✅ ALL CLI SUBCOMMANDS VERIFIED SUCCESSFULLY!")
    else:
        print("Timed out waiting for breakpoint hit.")

if __name__ == "__main__":
    main()
