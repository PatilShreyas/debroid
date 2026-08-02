import subprocess
import json
import os
import sys
import time

def main():
    jar_path = "build/libs/agentic-android-debugger-1.0.0.jar"
    java_bin = "/opt/homebrew/opt/openjdk/bin/java"
    if not os.path.exists(java_bin):
        java_bin = "java"

    subprocess.run([os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"), "shell", "am", "clear-debug-app"])

    proc = subprocess.Popen(
        [java_bin, "-jar", jar_path],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1
    )

    req_id = 1

    def send_rpc(method, params=None):
        nonlocal req_id
        msg = {"jsonrpc": "2.0", "id": req_id, "method": method}
        if params is not None:
            msg["params"] = params
        req_id += 1
        payload = json.dumps(msg) + "\n"
        proc.stdin.write(payload)
        proc.stdin.flush()
        resp_line = proc.stdout.readline()
        if not resp_line:
            return None
        return json.loads(resp_line)

    def call_tool(tool_name, args):
        resp = send_rpc("tools/call", {"name": tool_name, "arguments": args})
        if not resp or "result" not in resp:
            print(f"Error calling {tool_name}: {resp}", file=sys.stderr)
            return None
        content = resp["result"].get("content", [])
        if content and "text" in content[0]:
            return json.loads(content[0]["text"])
        return resp["result"]

    send_rpc("initialize")

    print("--- ATTACHING TO RUNNING APP ---")
    attach_res = call_tool("debug_attach", {"app_id": "com.example.sampledebugapp"})
    if not attach_res or "session_id" not in attach_res:
        attach_res = call_tool("debug_launch", {"app_id": "com.example.sampledebugapp"})

    session_id = attach_res["session_id"]
    print(f"Session ID: {session_id}")

    print("--- SETTING EXCEPTION BREAKPOINT FOR IllegalArgumentException ---")
    ex_res = call_tool("set_exception_breakpoint", {
        "session_id": session_id,
        "class_name": "java.lang.IllegalArgumentException"
    })
    print(f"Exception Breakpoint Set: {ex_res}")

    print("--- RESUMING ALL THREADS ---")
    call_tool("step_execution", {"session_id": session_id, "thread_id": "1", "action": "resume_all"})

    print("\n✅ EXCEPTION DEBUGGER ARMED FOR java.lang.IllegalArgumentException.")
    print("👉 Tap 'Trigger Exception Order' on the emulator now...")
    sys.stdout.flush()

    cursor = "0"
    hit = False
    thread_hit = None
    ex_msg = None

    start_time = time.time()
    while time.time() - start_time < 300: # Wait up to 5 minutes
        poll_res = call_tool("poll_events", {"session_id": session_id, "since_cursor": cursor})
        if poll_res:
            cursor = poll_res.get("next_cursor", cursor)
            events = poll_res.get("events", [])
            for ev in events:
                if ev.get("event_type") == "EXCEPTION_HIT":
                    hit = True
                    thread_hit = ev.get("thread_id", "1")
                    ex_msg = ev.get("exception_message")
                    break
        if hit:
            break
        time.sleep(1)

    if hit:
        print("\n===========================================")
        print("⚡ EXCEPTION CAUGHT BY DEBUGGER!")
        print("===========================================")
        print(f"Exception Type: {ex_msg}")
        pause_state = call_tool("get_pause_state", {"session_id": session_id, "thread_id": thread_hit})
        print("\nCALL STACK & LOCALS AT EXCEPTION:")
        print(json.dumps(pause_state, indent=2))
    else:
        print("Timed out waiting for exception hit.")

    proc.terminate()

if __name__ == "__main__":
    main()
