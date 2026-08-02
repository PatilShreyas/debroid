import subprocess
import json
import os
import sys
import time

def main():
    jar_path = "build/libs/debroid-1.0.0.jar"
    java_bin = "/opt/homebrew/opt/openjdk/bin/java"
    if not os.path.exists(java_bin):
        java_bin = "java"

    subprocess.run([os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"), "shell", "am", "clear-debug-app"])

    proc = subprocess.Popen(
        [java_bin, "-jar", jar_path, "--mcp"],
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

    print("--- ATTACHING TO RUNNING APP VIA MCP ---")
    attach_res = call_tool("debug_attach", {"app_id": "com.example.sampledebugapp"})
    if not attach_res or "session_id" not in attach_res:
        attach_res = call_tool("debug_launch", {"app_id": "com.example.sampledebugapp"})

    session_id = attach_res["session_id"]
    print(f"Session ID: {session_id}")

    print("--- SETTING FIELD WATCHPOINT ON totalOrdersProcessed VIA MCP ---")
    wp_res = call_tool("set_watchpoint", {
        "session_id": session_id,
        "class_name": "com.example.sampledebugapp.data.DefaultDataRepository",
        "field_name": "totalOrdersProcessed"
    })
    print(f"Watchpoint Set: {wp_res}")

    print("--- RESUMING ALL THREADS VIA MCP ---")
    call_tool("step_execution", {"session_id": session_id, "thread_id": "1", "action": "resume_all"})

    print("\n✅ MCP WATCHPOINT ARMED. Tap 'Calculate Regular Order' on the emulator now...")
    sys.stdout.flush()

    cursor = "0"
    hit = False
    thread_hit = None

    start_time = time.time()
    while time.time() - start_time < 300: # Wait up to 5 minutes
        poll_res = call_tool("poll_events", {"session_id": session_id, "since_cursor": cursor})
        if poll_res:
            cursor = poll_res.get("next_cursor", cursor)
            events = poll_res.get("events", [])
            for ev in events:
                if "WATCHPOINT" in ev.get("event_type", ""):
                    hit = True
                    thread_hit = ev.get("thread_id", "1")
                    print("\n🎯 WATCHPOINT EVENT CAPTURED VIA MCP:", ev)
                    break
        if hit:
            break
        time.sleep(1)

    if hit:
        print("\n===========================================")
        print("🎯 FIELD WATCHPOINT HIT CAUGHT VIA MCP!")
        print("===========================================")

        print("\n--- GETTING LOCALS AT WATCHPOINT HIT ---")
        locals_res = call_tool("get_variables", {"session_id": session_id, "thread_id": thread_hit, "scope": "LOCAL"})
        print("Locals:", json.dumps(locals_res, indent=2))

        print("\n--- RESUMING EXECUTION ---")
        call_tool("step_execution", {"session_id": session_id, "thread_id": thread_hit, "action": "resume_all"})
        print("✅ Execution resumed!")
    else:
        print("Timed out waiting for watchpoint hit.")

    time.sleep(3)
    proc.terminate()

if __name__ == "__main__":
    main()
