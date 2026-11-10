"""Loopback-only dev receiver. Requires WEBHOOK_KEYS_FILE or WEBHOOK_SECRET (32+ bytes)."""
import hashlib
import hmac
import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

KEYS_FILE = os.environ.get("WEBHOOK_KEYS_FILE")
SECRET = os.environ.get("WEBHOOK_SECRET", "").encode("utf-8")
if not KEYS_FILE and len(SECRET) < 32:
    raise SystemExit("Provide WEBHOOK_KEYS_FILE or WEBHOOK_SECRET (at least 32 bytes)")


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
            secret = SECRET
            if KEYS_FILE:
                with open(KEYS_FILE, encoding="utf-8") as key_file:
                    keys = json.load(key_file)
                key = keys[self.headers.get("PayFlow-Webhook-Key-Id", "")]
                if str(key["version"]) != self.headers.get("PayFlow-Webhook-Key-Version"):
                    raise ValueError("Key version mismatch")
                secret = key["secret"].encode("utf-8")
            expected = "v1=" + hmac.new(secret, timestamp.encode() + b"." + body, hashlib.sha256).hexdigest()
            if not hmac.compare_digest(expected, self.headers.get("PayFlow-Webhook-Signature", "")):
                raise ValueError("Invalid signature")
            event = json.loads(body)
            if str(event["eventId"]) != self.headers.get("PayFlow-Webhook-Id"):
                raise ValueError("Identity mismatch")
            print(json.dumps({"eventId": event["eventId"], "status": event["status"], "signatureValid": True, "keyReference": self.headers.get("PayFlow-Webhook-Key-Id"), "keyVersion": self.headers.get("PayFlow-Webhook-Key-Version")}), flush=True)
            self.send_response(204)
        except (ValueError, KeyError):
            self.send_response(400)
        self.end_headers()


if __name__ == "__main__":
    port = int(os.environ.get("MOCK_MERCHANT_PORT", "8090"))
    print(f"Development merchant listening on 127.0.0.1:{port}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Receiver).serve_forever()
