"""Loopback-only development receiver. Requires WEBHOOK_SECRET (at least 32 bytes)."""
import hashlib
import hmac
import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SECRET = os.environ["WEBHOOK_SECRET"].encode("utf-8")
if len(SECRET) < 32:
    raise SystemExit("WEBHOOK_SECRET must contain at least 32 bytes")


class Receiver(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Never log bodies, signatures, destination query strings, or secrets.

    def do_POST(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 65536:
                raise ValueError("Invalid body length")
            body = self.rfile.read(length)
            timestamp = self.headers.get("PayFlow-Webhook-Timestamp", "")
            if abs(time.time() - int(timestamp)) > 300:
                raise ValueError("Expired signature")
            expected = "v1=" + hmac.new(SECRET, timestamp.encode() + b"." + body, hashlib.sha256).hexdigest()
            if not hmac.compare_digest(expected, self.headers.get("PayFlow-Webhook-Signature", "")):
                raise ValueError("Invalid signature")
            event = json.loads(body)
            if str(event["eventId"]) != self.headers.get("PayFlow-Webhook-Id"):
                raise ValueError("Identity mismatch")
            print(json.dumps({"eventId": event["eventId"], "status": event["status"], "signatureValid": True}), flush=True)
            self.send_response(204)
        except (ValueError, KeyError):
            self.send_response(400)
        self.end_headers()


if __name__ == "__main__":
    port = int(os.environ.get("MOCK_MERCHANT_PORT", "8090"))
    print(f"Development merchant listening on 127.0.0.1:{port}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Receiver).serve_forever()
