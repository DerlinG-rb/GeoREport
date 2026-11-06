"""Institution simulation, standard-library only. No official integrations."""
import argparse
import base64
import hashlib
import hmac
import json
import math
import os
from pathlib import Path
import sqlite3
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from datetime import datetime, timezone
from uuid import uuid4

CATEGORIES = ["Bache / Vía en mal estado", "Falla de alumbrado público", "Basura / Escombros acumulados", "Fuga de agua / Alcantarillado", "Árbol / Rama con riesgo de caída", "Semáforo o señalización averiada", "Cableado / Poste con riesgo eléctrico", "Otro tipo de incidencia urbana"]
INSTITUTIONS = [
    {"id": "obras-demo", "name": "Obras Urbanas — DEMOSTRACIÓN", "categories": [CATEGORIES[i] for i in (0, 1, 5, 6, 7)]},
    {"id": "ambiente-demo", "name": "Ambiente y Agua — DEMOSTRACIÓN", "categories": [CATEGORIES[i] for i in (2, 3, 4, 7)]},
]
MAX_BODY = 30 * 1024 * 1024

class ApiError(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message

class Repository:
    def __init__(self, path):
        self.path = str(path)
        with self.connect() as db:
            db.execute('''CREATE TABLE IF NOT EXISTS received (
                receipt TEXT PRIMARY KEY, received_at TEXT NOT NULL,
                report_id TEXT NOT NULL, institution TEXT NOT NULL,
                payload TEXT NOT NULL, digest TEXT NOT NULL,
                UNIQUE(report_id, institution))''')
    def connect(self):
        return sqlite3.connect(self.path, timeout=15)
    def receive(self, data, key):
        required = ('report_id', 'category', 'description', 'location', 'created_at', 'photo', 'institution')
        if not isinstance(data, dict) or any(k not in data for k in required):
            raise ApiError(400, 'Faltan campos obligatorios')
        if set(data) != set(required):
            raise ApiError(400, 'Campos no permitidos; no envíe datos personales')
        for k in ('report_id', 'category', 'description', 'created_at', 'institution'):
            if not isinstance(data[k], str) or not data[k].strip() or len(data[k]) > 5000:
                raise ApiError(400, 'Campo inválido: ' + k)
        if len(data['report_id']) > 100:
            raise ApiError(400, 'Identificador demasiado largo')
        institution = next((i for i in INSTITUTIONS if i['id'] == data['institution']), None)
        if not institution or data['category'] not in institution['categories']:
            raise ApiError(400, 'Institución o categoría incompatible')
        if key != data['report_id'] + ':' + data['institution']:
            raise ApiError(400, 'Clave de idempotencia inválida')
        loc = data['location']
        if not isinstance(loc, dict) or set(loc) != {'latitude', 'longitude'}:
            raise ApiError(400, 'Ubicación inválida')
        for k, limit in [('latitude', 90), ('longitude', 180)]:
            v = loc[k]
            if type(v) not in (float, int) or not math.isfinite(v) or not -limit <= v <= limit:
                raise ApiError(400, 'Coordenadas inválidas')
        try:
            stamp = datetime.fromisoformat(data['created_at'].replace('Z', '+00:00'))
            if stamp.tzinfo is None:
                raise ValueError()
            photo = data['photo']
            if not isinstance(photo, str) or not photo.startswith('data:image/jpeg;base64,'):
                raise ValueError()
            raw = base64.b64decode(photo.split(',', 1)[1], validate=True)
            if not 4 <= len(raw) <= 20 * 1024 * 1024 or not raw.startswith(b'\xff\xd8') or not raw.endswith(b'\xff\xd9'):
                raise ValueError()
        except (ValueError, TypeError, AttributeError):
            raise ApiError(400, 'Fecha o fotografía JPEG inválida')
        payload = json.dumps(data, sort_keys=True, ensure_ascii=False, separators=(',', ':'))
        digest = hashlib.sha256(payload.encode()).hexdigest()
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            old = db.execute('SELECT receipt,received_at,digest FROM received WHERE report_id=? AND institution=?', (data['report_id'], data['institution'])).fetchone()
            if old:
                if not hmac.compare_digest(old[2], digest):
                    raise ApiError(409, 'La clave ya fue usada con otro contenido')
                return {'receipt_id': old[0], 'received_at': old[1]}, False
            receipt, at = str(uuid4()), datetime.now(timezone.utc).isoformat()
            db.execute('INSERT INTO received VALUES (?,?,?,?,?,?)', (receipt, at, data['report_id'], data['institution'], payload, digest))
            return {'receipt_id': receipt, 'received_at': at}, True
    def list(self, institution):
        with self.connect() as db:
            rows = db.execute('SELECT receipt,received_at,payload FROM received WHERE institution=? ORDER BY received_at DESC LIMIT 500', (institution,)).fetchall()
        return [dict(receipt_id=r[0], received_at=r[1], **{k:v for k,v in json.loads(r[2]).items() if k != 'photo'}) for r in rows]
    def detail(self, receipt, institution):
        with self.connect() as db:
            row = db.execute('SELECT received_at,payload FROM received WHERE receipt=? AND institution=?', (receipt, institution)).fetchone()
        if not row:
            raise ApiError(404, 'Recepción no encontrada')
        return dict(receipt_id=receipt, received_at=row[0], **json.loads(row[1]))


def handler(repository, integration_token, portal_tokens):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass # Do not log credentials or citizen evidence.
        def reply(self, status, value, content_type='application/json; charset=utf-8'):
            raw = json.dumps(value, ensure_ascii=False).encode() if content_type.startswith('application/json') else value
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(raw)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Content-Security-Policy', "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; frame-ancestors 'none'")
            self.end_headers()
            self.wfile.write(raw)
        def auth(self):
            token = self.headers.get('Authorization', '').removeprefix('Bearer ')
            if hmac.compare_digest(token, integration_token):
                return 'integration', None
            for institution, secret in portal_tokens.items():
                if hmac.compare_digest(token, secret):
                    return 'institution', institution
            raise ApiError(401, 'Acceso de demostración requerido')
        def do_GET(self):
            try:
                if self.path in ('/', '/portal.js', '/style.css'):
                    name = {'/': 'index.html', '/portal.js': 'portal.js', '/style.css': 'style.css'}[self.path]
                    kind = {'/': 'text/html', '/portal.js': 'text/javascript', '/style.css': 'text/css'}[self.path]
                    return self.reply(200, (Path(__file__).parent / 'portal' / name).read_bytes(), kind + '; charset=utf-8')
                role, scope = self.auth()
                if self.path == '/institutions':
                    return self.reply(200, [i for i in INSTITUTIONS if role == 'integration' or i['id'] == scope])
                if role != 'institution':
                    raise ApiError(403, 'Use una cuenta de institución para consultar evidencias')
                if self.path == '/reports':
                    return self.reply(200, repository.list(scope))
                if self.path.startswith('/reports/'):
                    return self.reply(200, repository.detail(self.path.split('/')[-1], scope))
                raise ApiError(404, 'Ruta no encontrada')
            except (BrokenPipeError, ConnectionResetError):
                pass # Client may retry after a committed reception.
            except ApiError as e:
                self.reply(e.status, {'error': e.message})
            except Exception:
                self.reply(500, {'error': 'Error de almacenamiento; reintente'})
        def do_POST(self):
            try:
                role, _ = self.auth()
                if role != 'integration':
                    raise ApiError(403, 'Solo la integración puede enviar')
                if self.path != '/reports':
                    raise ApiError(404, 'Ruta no encontrada')
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= MAX_BODY:
                    raise ApiError(413, 'Tamaño de petición inválido')
                if self.headers.get('Content-Type', '').split(';')[0] != 'application/json':
                    raise ApiError(415, 'Se requiere JSON')
                self.connection.settimeout(20)
                data = json.loads(self.rfile.read(length))
                receipt, created = repository.receive(data, self.headers.get('Idempotency-Key'))
                self.reply(201 if created else 200, receipt)
            except (BrokenPipeError, ConnectionResetError):
                pass # Client may retry after a committed reception.
            except ApiError as e:
                self.reply(e.status, {'error': e.message})
            except (ValueError, UnicodeError):
                self.reply(400, {'error': 'JSON inválido'})
            except Exception:
                self.reply(500, {'error': 'No se pudo confirmar; reintente con la misma clave'})
    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8080)
    parser.add_argument('--database', default='institution/received.sqlite3')
    args = parser.parse_args()
    token = os.environ.get('GEOREPORT_INTEGRATION_TOKEN', '')
    portals = json.loads(os.environ.get('GEOREPORT_PORTAL_TOKENS', '{}'))
    valid_ids = {i['id'] for i in INSTITUTIONS}
    if len(token) < 24 or not portals or not set(portals) <= valid_ids or any(not isinstance(v, str) or len(v) < 24 for v in portals.values()) or len(set([token, *portals.values()])) != len(portals) + 1:
        parser.error('Configure tokens distintos de al menos 24 caracteres; consulte docs/DEMO.md')
    server = ThreadingHTTPServer((args.host, args.port), handler(Repository(args.database), token, portals))
    print(f'Institución simulada — demostración académica: http://{args.host}:{args.port}', flush=True)
    server.serve_forever()

if __name__ == '__main__':
    main()
