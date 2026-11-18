"""Forward the Android test's private model stream and record which inputs answered the panel."""

import base64
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
        image_data = [
            part["image_url"]["url"].split(",", 1)[1]
            for message in messages if isinstance(message["content"], list)
            for part in message["content"] if part.get("type") == "image_url"
        ]
        images = len(image_data)
        image_bytes = sum(len(base64.b64decode(encoded)) for encoded in image_data)
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
        record = {"images": images, "image_bytes": image_bytes, "regions": len(regions), "story_entries": len(history),
                  "purpose": purpose, "review_items": review_items,
                  "ocr_hints": sum(bool(region.get("ocr_text_maybe_garbled")) for region in regions),
                  "completed": False}
        print(f"Android model input: {purpose}, {images} images, {image_bytes} bytes, {review_items} review items", flush=True)
        start = time.monotonic()
        backend = http.client.HTTPConnection("127.0.0.1", 7878, timeout=240)
        try:
            backend.request("POST", self.path, raw, {"Content-Type": "application/json", "Connection": "close"})
            reply = backend.getresponse()
            self.send_response(reply.status)
            self.send_header("Content-Type", reply.getheader("Content-Type", "application/json"))
            self.end_headers()
            response = bytearray()
            while chunk := reply.read1(8192):
                if len(response) < 524288:
                    response.extend(chunk[:524288 - len(response)])
                if "first_bytes_ms" not in record:
                    record["first_bytes_ms"] = round((time.monotonic() - start) * 1000)
                self.wfile.write(chunk)
                self.wfile.flush()
            record["completed"] = 200 <= reply.status < 300
            # This observer only runs with credited public panels. Keep the model's
            # own words so the image reading and any text-only correction can be audited.
            try:
                wire = response.decode()
                if "event-stream" in reply.getheader("Content-Type", "") or wire.lstrip().startswith("data:"):
                    parts = (json.loads(line[6:]) for line in wire.splitlines()
                             if line.startswith("data: ") and line[6:] != "[DONE]")
                    answer = "".join(p["choices"][0]["delta"].get("content", "") for p in parts if p.get("choices"))
                else:
                    answer = json.loads(wire)["choices"][0]["message"]["content"]
                body = json.loads(answer.strip().removeprefix("```json").removesuffix("```").strip())
                record["answers"] = body.get("bubbles", body.get("items", []))
            except (ValueError, KeyError, IndexError, TypeError, AttributeError):
                pass
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
