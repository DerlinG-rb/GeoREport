import copy
import json
import tempfile
import threading
import socket
import unittest
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor
from http.server import ThreadingHTTPServer
from pathlib import Path
from institution.server import Repository, handler, CATEGORIES, ApiError

class ApiTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / 'received.db'
        self.repo = Repository(self.path)
        self.integration = 'integration-test-token-123456'
        self.portal = 'institution-test-token-123456'
        self.other = 'other-institution-token-123456'
        self.server = ThreadingHTTPServer(('127.0.0.1',0),handler(self.repo,self.integration,{'obras-demo':self.portal,'ambiente-demo':self.other}))
        self.thread = threading.Thread(target=self.server.serve_forever,daemon=True); self.thread.start()
        self.url = f'http://127.0.0.1:{self.server.server_port}'
        self.data = {'report_id':'DEMO-test','category':CATEGORIES[0],'description':'Incidencia ficticia de prueba', 'location':{'latitude':-2.19,'longitude':-79.889}, 'created_at':'2026-11-06T12:00:00Z', 'photo':'data:image/jpeg;base64,/9j/2Q==','institution':'obras-demo'}
    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(); self.tmp.cleanup()
    def request(self,path,token=None,data=None):
        headers = {'Authorization':'Bearer '+(token or ''),'Content-Type':'application/json','Idempotency-Key':'DEMO-test:obras-demo'}
        request = urllib.request.Request(self.url+path,data=json.dumps(data).encode() if data else None,headers=headers)
        try:
            with urllib.request.urlopen(request,timeout=3) as response:
                return response.status,json.load(response)
        except urllib.error.HTTPError as e:
            return e.code,json.load(e)
    def test_real_http_idempotency_restart_and_lost_response(self):
        # Commit occurs, response is discarded by caller; retry across repository reopening.
        first = self.request('/reports',self.integration,self.data)
        Repository(self.path)
        retry = self.request('/reports',self.integration,self.data)
        self.assertEqual(201,first[0]); self.assertEqual(200,retry[0]); self.assertEqual(first[1],retry[1])
        rows = self.request('/reports',self.portal)[1]
        self.assertEqual(1,len(rows)); self.assertNotIn('photo',rows[0])
        detail = self.request('/reports/'+rows[0]['receipt_id'],self.portal)
        self.assertEqual(self.data['photo'],detail[1]['photo'])
    def test_actual_timeout_after_commit_recovers_receipt(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join()
        committed, release = threading.Event(), threading.Event()
        base = handler(self.repo,self.integration,{'obras-demo':self.portal})
        class Delayed(base):
            def reply(self,status,value,content_type='application/json; charset=utf-8'):
                if status == 201:
                    committed.set(); release.wait(2)
                super().reply(status,value,content_type)
        self.server = ThreadingHTTPServer(('127.0.0.1',0),Delayed)
        self.thread = threading.Thread(target=self.server.serve_forever,daemon=True); self.thread.start()
        self.url = f'http://127.0.0.1:{self.server.server_port}'
        request = urllib.request.Request(self.url+'/reports',data=json.dumps(self.data).encode(),headers={
            'Authorization':'Bearer '+self.integration,'Content-Type':'application/json','Idempotency-Key':'DEMO-test:obras-demo'})
        try:
            with self.assertRaises((TimeoutError,socket.timeout)):
                urllib.request.urlopen(request,timeout=.1)
            self.assertTrue(committed.wait(1))
        finally:
            release.set()
        rows = self.repo.list('obras-demo')
        retry = self.request('/reports',self.integration,self.data)
        self.assertEqual(200,retry[0]); self.assertEqual(rows[0]['receipt_id'],retry[1]['receipt_id'])
        self.assertEqual(1,len(self.repo.list('obras-demo')))

    def test_auth_and_tenant_isolation(self):
        self.assertEqual(401,self.request('/reports')[0])
        self.assertEqual(403,self.request('/reports',self.portal,self.data)[0])
        receipt = self.request('/reports',self.integration,self.data)[1]['receipt_id']
        self.assertEqual([],self.request('/reports',self.other)[1])
        self.assertEqual(404,self.request('/reports/'+receipt,self.other)[0])
        self.assertEqual(403,self.request('/reports',self.integration)[0])
        self.assertEqual(1,len(self.request('/institutions',self.portal)[1]))
    def test_invalid_fields_and_personal_data_rejected(self):
        for field,value in [('institution','real-office'),('description',''),('photo','file:///private/evidence'),('created_at','not-a-date'),('location',{'latitude':91,'longitude':0}),('location',{'latitude':True,'longitude':0})]:
            data = copy.deepcopy(self.data); data[field] = value
            self.assertEqual(400,self.request('/reports',self.integration,data)[0],field)
        data = dict(self.data,email='private@example.com')
        self.assertEqual(400,self.request('/reports',self.integration,data)[0])
        self.assertEqual([],self.repo.list('obras-demo'))
    def test_payload_conflict(self):
        self.request('/reports',self.integration,self.data)
        self.data['description'] = 'Changed'
        self.assertEqual(409,self.request('/reports',self.integration,self.data)[0])
    def test_concurrent_requests_do_not_duplicate(self):
        with ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(lambda _: self.request('/reports',self.integration,self.data),range(8)))
        self.assertEqual(1,len({r[1]['receipt_id'] for r in results}))
        self.assertEqual(1,len(self.repo.list('obras-demo')))
    def test_idempotency_key_required(self):
        with self.assertRaises(ApiError): self.repo.receive(self.data,'wrong')
        self.assertEqual([],self.repo.list('obras-demo'))

if __name__ == '__main__': unittest.main()
