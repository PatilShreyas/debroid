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

    patched_code = """package com.example.sampledebugapp.data

data class Order(val id: String, val amount: Double, val customerType: String)

class OrderProcessor {
    fun calculateTotal(order: Order): Double {
        val discount = when (order.customerType) {
            "REGULAR" -> 0.05
            "GOLD" -> 0.15
            "PLATINUM" -> 0.25
            else -> 0.0
        }
        val tax = order.amount * 0.10
        return (order.amount - (order.amount * discount)) + tax
    }
}
"""

    print("\n--- HOT-SWAPPING OrderProcessor VIA patch_class ---")
    patch_res = call_tool("patch_class", {
        "session_id": session_id,
        "class_name": "com.example.sampledebugapp.data.OrderProcessor",
        "full_source_code": patched_code
    })
    print("PATCH RESULT:", json.dumps(patch_res, indent=2))

    print("\n--- RESUMING EXECUTION ---")
    call_tool("step_execution", {"session_id": session_id, "thread_id": "1", "action": "resume_all"})

    proc.terminate()

if __name__ == "__main__":
    main()
