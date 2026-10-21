#!/usr/bin/env python3
"""Loopback-only Drive metadata/payload fixture; generated data and fake tokens only.

DriveMetadataIsolationDeviceTest arms a real 60s metadata read timeout, followed
by dropped responses until retries exhaust. Eight payload sessions keep receiving
bytes. /release restores metadata and removes payload throttling.
"""
import argparse
import hashlib
import http.server
import json
import re
import threading
import time
from urllib.parse import urlsplit, parse_qs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=18766)
    args = parser.parse_args()
    lock = threading.RLock()
    entries, contents, sessions = {}, {}, {}
    fault, released, failures = False, False, 0

    def save(metadata, data=b'', identity=None):
        identity = identity or f'file-{len(entries)}'
        value = dict(entries.get(identity, {}), **metadata, id=identity,
                     size=str(len(data)), md5Checksum=hashlib.md5(data).hexdigest(),
                     createdTime='2020-01-01T00:00:00Z')
        value.setdefault('mimeType', 'application/octet-stream')
        entries[identity], contents[identity] = value, data
        return value

    for identity, name, parent in [('r', 'Hoshi Reader', 'root'), ('s', 'state', 'r'), ('b', 'books', 'r')]:
        save(dict(name=name, parents=[parent], mimeType='application/vnd.google-apps.folder'), identity=identity)

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *args):
            pass

        def reply(self, code=200, data=None, headers=None):
            body = data if isinstance(data, bytes) else json.dumps(data or {}).encode()
            try:
                self.send_response(code)
                self.send_header('Content-Length', str(len(body)))
                for key, value in (headers or {}).items():
                    self.send_header(key, value)
                self.end_headers()
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def do_GET(self):
            nonlocal failures
            parsed = urlsplit(self.path)
            path, query = parsed.path, parse_qs(parsed.query)
            if path == '/stats':
                with lock:
                    return self.reply(data=dict(failures=failures, received={k: v['received'] for k, v in sessions.items()}, completed=sum('result' in v for v in sessions.values()), published=sum(b'"epub"' in contents[k] for k, v in entries.items() if v.get('parents') == ['s'])))
            path = path.split('/drive/v3/')[-1]
            if path == 'changes' and fault:
                with lock:
                    failures += 1
                    first = failures == 1
                if first:
                    time.sleep(65)  # actual Android HttpURLConnection 60s read timeout
                self.close_connection = True
                return
            with lock:
                if path == 'changes/startPageToken':
                    return self.reply(data={'startPageToken': 'start'})
                if path == 'changes':
                    return self.reply(data={'newStartPageToken': 'next', 'changes': [dict(removed=False, file=v) for v in entries.values() if v.get('parents') == ['s']]})
                if path == 'files':
                    q = query.get('q', [''])[0]
                    parent = re.search(r"'([^']+)' in parents", q)
                    name = re.search(r"name='([^']*)'", q)
                    return self.reply(data={'files': [v for v in entries.values() if (not parent or parent[1] in v.get('parents', [])) and (not name or name[1] == v['name'])]})
                identity = path.removeprefix('files/')
                return self.reply(data=contents[identity]) if identity in contents else self.reply(404)

        def do_POST(self):
            nonlocal fault, released
            data = self.rfile.read(int(self.headers.get('Content-Length', 0)))
            if self.path == '/fault':
                fault = True
                return self.reply()
            if self.path == '/release':
                fault, released = False, True
                return self.reply()
            with lock:
                if 'uploadType=resumable' in self.path:
                    identity = str(len(sessions))
                    sessions[identity] = dict(metadata=json.loads(data), received=0, confirmed=0,
                                              digest=hashlib.md5(), size=int(self.headers['X-Upload-Content-Length']))
                    return self.reply(headers={'Location': f'http://127.0.0.1:{args.port}/session/{identity}'})
                if 'uploadType=multipart' in self.path:
                    parts = data.split(b'\r\n--')
                    metadata = json.loads(parts[0].split(b'\r\n\r\n', 1)[1])
                    body = parts[1].split(b'\r\n\r\n', 1)[1]
                    identity = urlsplit(self.path).path.split('/files/')[-1] if self.command == 'PATCH' else None
                    return self.reply(data=save(metadata, body, identity))
                return self.reply(data=save(json.loads(data)))

        do_PATCH = do_POST

        def do_PUT(self):
            identity = self.path.split('/')[-1]
            session = sessions[identity]
            length = int(self.headers.get('Content-Length', 0))
            content_range = self.headers.get('Content-Range', '')
            if length:
                match = re.fullmatch(r'bytes (\d+)-(\d+)/(\d+)', content_range)
                if not match or int(match[1]) != session['confirmed'] or int(match[2]) - int(match[1]) + 1 != length or int(match[3]) != session['size']:
                    self.close_connection = True
                    return self.reply(400)
            remaining = length
            while remaining:
                chunk = self.rfile.read(min(65536, remaining))
                if not chunk:
                    return
                with lock:
                    session['digest'].update(chunk)
                    session['received'] += len(chunk)
                remaining -= len(chunk)
                if not released:
                    time.sleep(.4)
            with lock:
                session['confirmed'] += length
                if session['confirmed'] == session['size']:
                    if 'result' not in session:
                        result = save(session['metadata'])
                        result.update(size=str(session['size']), md5Checksum=session['digest'].hexdigest())
                        session['result'] = result
                    return self.reply(data=session['result'])
                return self.reply(308, data=b'', headers={'Range': f"bytes=0-{session['confirmed'] - 1}"} if session['confirmed'] else {})

    server = http.server.ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    print(f'Metadata isolation fixture on 127.0.0.1:{args.port}', flush=True)
    server.serve_forever()


if __name__ == '__main__':
    main()
