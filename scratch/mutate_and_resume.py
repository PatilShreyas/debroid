import subprocess
import json
import os
import sys

def main():
    jar_path = "build/libs/agentic-android-debugger-1.0.0.jar"
    java_bin = "/opt/homebrew/opt/openjdk/bin/java"
    if not os.path.exists(java_bin):
        java_bin = "java"

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

    print("--- ATTACHING TO PAUSED SESSION ---")
    attach_res = call_tool("debug_attach", {"app_id": "com.example.sampledebugapp"})
    session_id = attach_res["session_id"]
    print(f"Session ID: {session_id}")

    print("\n--- MUTATING VARIABLE 'amount' TO 500.0 ---")
    mutate_res = call_tool("set_variable", {
        "session_id": session_id,
        "thread_id": "1",
        "variable_name": "amount",
        "new_value": "500.0"
    })
    print(f"Mutation Result: {mutate_res}")

    print("\n--- CONFIRMING LOCAL VARIABLES AFTER MUTATION ---")
    locals_res = call_tool("get_variables", {
        "session_id": session_id,
        "thread_id": "1",
        "scope": "local"
    })
    print("Updated Locals:")
    print(json.dumps(locals_res, indent=2))

    print("\n--- RESUMING EXECUTION WITH MUTATED VALUE ---")
    call_tool("step_execution", {
        "session_id": session_id,
        "thread_id": "1",
        "action": "resume_all"
    })
    print("App resumed successfully!")

    proc.terminate()

if __name__ == "__main__":
    main()
