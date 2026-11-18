"""Forward the Android test's private model stream and record which inputs answered the panel."""

import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import sys
import time


class ModelObserver(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200 if self.path == "/health" else 404)
        self.end_headers()

    def do_POST(self):
        if self.path != "/v1/chat/completions":
            self.send_error(404)
            return
        raw = self.rfile.read(int(self.headers["Content-Length"]))
        messages = json.loads(raw)["messages"]
        images = sum(
            part.get("type") == "image_url"
            for message in messages if isinstance(message["content"], list)
            for part in message["content"]
        )
        regions = []
        history = []
        purpose = "page" if images else "unknown"
        review_items = 0
        for message in messages:
            if not isinstance(message["content"], list):
                if message["role"] == "user" and not images:
                    try:
                        payload = json.loads(message["content"].rsplit("\n\n", 1)[-1])
                    except (ValueError, TypeError):
                        continue
                    if isinstance(payload, dict) and payload.get("review") == "forms_of_address":
                        purpose = "forms_of_address"
                        review_items = len(payload.get("items", []))
                continue
            for part in message["content"]:
                if part.get("type") == "text":
                    try:
                        payload = json.loads(part["text"])
                    except json.JSONDecodeError:
                        continue
                    if isinstance(payload, dict):
                        regions.extend(payload.get("detected_regions", []))
                        history.extend(payload.get("story_so_far", []))
        record = {"images": images, "regions": len(regions), "story_entries": len(history),
                  "purpose": purpose, "review_items": review_items,
                  "ocr_hints": sum(bool(region.get("ocr_text_maybe_garbled")) for region in regions),
                  "completed": False}
        start = time.monotonic()
        backend = http.client.HTTPConnection("127.0.0.1", 7878, timeout=240)
        try:
            backend.request("POST", self.path, raw, {"Content-Type": "application/json", "Connection": "close"})
            reply = backend.getresponse()
            self.send_response(reply.status)
            self.send_header("Content-Type", reply.getheader("Content-Type", "application/json"))
            self.end_headers()
            while chunk := reply.read1(8192):
                if "first_bytes_ms" not in record:
                    record["first_bytes_ms"] = round((time.monotonic() - start) * 1000)
                self.wfile.write(chunk)
                self.wfile.flush()
            record["completed"] = 200 <= reply.status < 300
        finally:
            backend.close()
            record["elapsed_ms"] = round((time.monotonic() - start) * 1000)
            with self.server.report.open("a", encoding="utf-8") as report:
                report.write(json.dumps(record) + "\n")


if __name__ == "__main__":
    with ThreadingHTTPServer(("127.0.0.1", 7879), ModelObserver) as server:
        server.report = Path(sys.argv[1])
        server.report.write_text("", encoding="utf-8")
        server.serve_forever()
