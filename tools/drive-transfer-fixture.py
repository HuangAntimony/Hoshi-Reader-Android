#!/usr/bin/env python3
"""Loopback-only, generated-data endpoint for DriveTransferDeviceTest (no credentials)."""
import argparse
import http.server
import json
import re
import threading
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=18765)
    args = parser.parse_args()
    payload = bytearray()
    events = []
    resumed = False
    lock = threading.Lock()
    size = 2 * 1024 * 1024 + 17
    completed = dict(id='fixture-upload', name='generated.epub',
                     mimeType='application/epub+zip', size=str(size), createdTime='')

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *args):
            pass

        def reply(self, code=200, data=None, headers=None):
            body = b'' if data is None else json.dumps(data).encode()
            self.send_response(code)
            self.send_header('Content-Length', str(len(body)))
            for key, value in (headers or {}).items():
                self.send_header(key, value)
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def do_POST(self):
            nonlocal resumed
            self.rfile.read(int(self.headers.get('Content-Length', 0)))
            if self.path == '/allow-resume':
                resumed = True
                return self.reply(data={'ready': True})
            if not self.path.startswith('/upload?'):
                return self.reply(404)
            events.append({'method': 'POST'})
            self.reply(headers={'Location': f'http://127.0.0.1:{args.port}/session'})

        def do_PUT(self):
            if self.path != '/session':
                return self.reply(404)
            content_range = self.headers.get('Content-Range', '')
            length = int(self.headers.get('Content-Length', 0))
            body = bytearray()
            while len(body) < length:
                chunk = self.rfile.read(min(65536, length - len(body)))
                if not chunk:
                    return
                body.extend(chunk)
                time.sleep(.1)
            with lock:
                events.append({'method': 'PUT', 'range': content_range, 'length': length})
                if content_range.startswith('bytes */'):
                    if len(payload) == size:
                        return self.reply(data=completed)
                    return self.reply(308, headers={'Range': f'bytes=0-{len(payload)-1}'} if payload else {})
                match = re.fullmatch(r'bytes (\d+)-(\d+)/(\d+)', content_range)
                if not match or int(match[1]) != len(payload):
                    return self.reply(400)
                if not resumed and len(payload) >= 1024 * 1024:
                    # Drop a fully received request before acknowledging it.
                    self.close_connection = True
                    return
                payload.extend(body)
                if len(payload) == size:
                    return self.reply(data=completed)
                self.reply(308, headers={'Range': f'bytes=0-{len(payload)-1}'})

        def do_GET(self):
            if self.path == '/stats':
                return self.reply(data={'confirmed': len(payload), 'events': events})
            if self.path != '/download':
                return self.reply(404)
            self.send_response(200)
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            for offset in range(0, len(payload), 65536):
                try:
                    self.wfile.write(payload[offset:offset+65536])
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError):
                    return
                time.sleep(.1)

    server = http.server.ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    print(f'Fixture listening on 127.0.0.1:{args.port}', flush=True)
    server.serve_forever()


if __name__ == '__main__':
    main()
