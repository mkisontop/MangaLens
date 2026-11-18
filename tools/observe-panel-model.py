"""Forward the Android test's private model stream and record which inputs answered the panel."""

import base64
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import struct
import sys
import time
from threading import Event


def jpeg_size(data):
    position = 2
    while data[position:position + 1] == b"\xff":
        marker = data[position + 1]
        length = int.from_bytes(data[position + 2:position + 4], "big")
        if marker in (0xc0, 0xc1, 0xc2):
            height, width = struct.unpack_from(">HH", data, position + 5)
            return [width, height]
        if length < 2 or marker in (0xda, 0xd9):
            break
        position += length + 2
    raise ValueError("Missing JPEG dimensions")


class StreamTiming:
    """Observe actual completion deltas. Keep-alives never count as content."""
    def __init__(self, record, start):
        self.record = record
        self.start = start
        self.pending = bytearray()
        self.text = []
        self.decoder = json.JSONDecoder()

    def accept(self, chunk):
        self.pending.extend(chunk)
        while (end := self.pending.find(b"\n")) >= 0:
            line = bytes(self.pending[:end]).rstrip(b"\r")
            del self.pending[:end + 1]
            if not line.startswith(b"data:"):
                continue
            data = line[5:].strip()
            if not data or data == b"[DONE]":
                continue
            try:
                event = json.loads(data)
            except ValueError:
                continue
            if event.get("timings"):
                self.record["provider_timings"] = event["timings"]
            if event.get("usage"):
                self.record["usage"] = event["usage"]
            for choice in event.get("choices") or []:
                value = (choice.get("delta") or {}).get("content")
                if isinstance(value, str) and value:
                    self.text.append(value)
                    self.record.setdefault("first_content_ms", round(1000 * (time.monotonic() - self.start)))

    def finish(self, wire):
        answer = "".join(self.text)
        if not answer:
            try:
                response = json.loads(wire)
                answer = response["choices"][0]["message"]["content"]
                if response.get("timings"):
                    self.record["provider_timings"] = response["timings"]
                if response.get("usage"):
                    self.record["usage"] = response["usage"]
            except (ValueError, KeyError, TypeError, IndexError):
                return
        left = answer.find("{")
        if left < 0:
            return
        try:
            parsed, _ = self.decoder.raw_decode(answer[left:])
            self.record["answers"] = parsed.get("bubbles", parsed.get("items", []))
        except (ValueError, AttributeError):
            self.record["unparsed_content_chars"] = len(answer)


class ModelObserver(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/completed-page":
            self.send_response(200 if self.server.completed_page.wait(timeout=1) else 202)
        else:
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
        decoded = [base64.b64decode(encoded) for encoded in image_data]
        image_bytes = sum(map(len, decoded))
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
                    if isinstance(payload, dict) and payload.get("review") in ("forms_of_address", "japanese_requests", "dialogue_roles"):
                        purpose = payload["review"]
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
        record = {"images": images, "image_bytes": image_bytes, "image_dimensions": [jpeg_size(data) for data in decoded], "regions": len(regions), "story_entries": len(history),
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
            timing = StreamTiming(record, start)
            while chunk := reply.read1(8192):
                if len(response) < 524288:
                    response.extend(chunk[:524288 - len(response)])
                if "first_bytes_ms" not in record:
                    record["first_bytes_ms"] = round((time.monotonic() - start) * 1000)
                self.wfile.write(chunk)
                self.wfile.flush()
                timing.accept(chunk)
            record["completed"] = 200 <= reply.status < 300
            timing.finish(response)
        finally:
            backend.close()
            record["elapsed_ms"] = round((time.monotonic() - start) * 1000)
            with self.server.report.open("a", encoding="utf-8") as report:
                report.write(json.dumps(record) + "\n")
            if record["images"] and record["completed"]:
                self.server.completed_page.set()


if __name__ == "__main__":
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 7879
    with ThreadingHTTPServer(("127.0.0.1", port), ModelObserver) as server:
        server.completed_page = Event()
        server.report = Path(sys.argv[1])
        server.report.write_text("", encoding="utf-8")
        server.serve_forever()
