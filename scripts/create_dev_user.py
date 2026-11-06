#!/usr/bin/env python3
"""Initialize a local debug account through adb run-as. Does not ship in APK."""
import argparse
import getpass
import hashlib
import json
import secrets
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--email', required=True)
parser.add_argument('--name', default='Cuenta de DEMOSTRACIÓN')
parser.add_argument('--role', choices=['CIUDADANO', 'ADMINISTRADOR'], default='CIUDADANO')
parser.add_argument('--serial', help='adb device serial')
args = parser.parse_args()
email = args.email.strip().lower()
if '@' not in email:
    parser.error('Correo inválido')
password = getpass.getpass('Contraseña (mínimo 6 caracteres): ')
if len(password) < 6 or password != getpass.getpass('Confirme contraseña: '):
    parser.error('Contraseñas diferentes o demasiado corta')
adb = ['adb'] + (['-s', args.serial] if args.serial else [])
package = 'com.example.georeport'
def run(parts, **kwargs):
    return subprocess.run(adb + parts, check=True, **kwargs)
run(['shell', 'am', 'force-stop', package], stdout=subprocess.DEVNULL)
run(['shell', 'run-as', package, 'id'], stdout=subprocess.DEVNULL)
path = 'shared_prefs/georeport_users.xml'
exists = subprocess.run(adb + ['shell', 'run-as', package, 'test', '-f', path]).returncode == 0
if exists:
    raw = subprocess.check_output(adb + ['exec-out', 'run-as', package, 'cat', path])
    root = ET.fromstring(raw)
else:
    root = ET.Element('map')
node = root.find("./string[@name='users']")
if node is None:
    node = ET.SubElement(root, 'string', {'name': 'users'})
users = json.loads(node.text or '{}')
if email in users:
    parser.error('La cuenta ya existe; no se modifica ni se eleva un usuario existente')
salt = secrets.token_hex(16)
digest = hashlib.sha256((salt + password).encode()).digest()
for _ in range(9999):
    digest = hashlib.sha256(digest).digest()
users[email] = {'name': args.name, 'phone': '', 'salt': salt, 'hash': digest.hex(), 'role': args.role}
node.text = json.dumps(users, ensure_ascii=False)
# Credentials travel via stdin, never command arguments. Atomic replacement, app is stopped.
run(['shell', 'run-as', package, 'mkdir', '-p', 'shared_prefs'])
run(['shell', 'run-as', package, 'sh', '-c', "'umask 077; cat > shared_prefs/georeport_users.xml.tmp; mv shared_prefs/georeport_users.xml.tmp shared_prefs/georeport_users.xml'"], input=ET.tostring(root, encoding='utf-8', xml_declaration=True))
print('Cuenta de desarrollo creada. Abra GeoReport e inicie sesión.')
