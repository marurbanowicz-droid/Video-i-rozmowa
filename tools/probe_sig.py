import asyncio, json, random, string, time, traceback
import websockets

def rid(n=10): return ''.join(random.choice(string.ascii_lowercase+string.digits) for _ in range(n))
def url(i, t): return f"wss://0.peerjs.com:443/peerjs?key=peerjs&id={i}&token={t}&version=1.5.4"
LOG = []
T0 = time.time()
def log(k, v): LOG.append(f"{time.time()-T0:5.1f} {k}: {v}")

class Conn:
    def __init__(self, name, i, tok, hb=True):
        self.name, self.id, self.tok, self.hb = name, i, tok, hb
        self.msgs = []; self.closed = None
    async def open(self):
        self.ws = await websockets.connect(url(self.id, self.tok), ping_interval=None)
        self.t = time.time()
        asyncio.create_task(self._rx())
        if self.hb: asyncio.create_task(self._hb())
        return self
    async def _rx(self):
        try:
            async for m in self.ws:
                self.msgs.append(m[:140]); log(self.name + " rx", m[:140])
        except Exception as e: pass
        self.closed = time.time() - self.t
        log(self.name + " closed", f"after {self.closed:.1f}s code={self.ws.close_code}")
    async def _hb(self):
        try:
            while True:
                await asyncio.sleep(5); await self.ws.send('{"type":"HEARTBEAT"}')
        except Exception: pass
    async def send(self, typ, dst, payload):
        try: await self.ws.send(json.dumps({"type": typ, "dst": dst, "payload": payload})); log(self.name + " tx", f"{typ} {payload.get('type')}")
        except Exception as e: log(self.name + " tx-fail", repr(e)[:80])

SDP = {"type": "offer", "sdp": "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"}

async def main():
    try:
        B = await Conn("B", "tnw-probe-" + rid(), rid(12)).open()
        S = [await Conn(f"S{k}", "tnw-probe-" + rid(), rid(12)).open() for k in range(6)]
        await asyncio.sleep(1.5)
        # V1: typ "ring" + sdp
        await S[0].send("OFFER", B.id, {"sdp": SDP, "type": "ring", "connectionId": "ring_" + rid(), "room": "ABCD"})
        await asyncio.sleep(2)
        # V2: typ "data" + sdp + metadata.ring
        await S[1].send("OFFER", B.id, {"sdp": SDP, "type": "data", "connectionId": "dc_" + rid(), "label": "x", "reliable": True,
                                        "serialization": "json", "metadata": {"ring": {"room": "ABCD", "roomName": "Test", "turn": ""}}})
        await asyncio.sleep(2)
        # V3: typ "media" + sdp + metadata.ringDecline
        await S[2].send("OFFER", B.id, {"sdp": SDP, "type": "media", "connectionId": "mc_" + rid(), "metadata": {"ringDecline": True, "name": "Ola", "cid": "Y"}})
        await asyncio.sleep(2)
        # V4: minimalne sdp
        await S[3].send("OFFER", B.id, {"sdp": {"type": "offer", "sdp": "ring"}, "type": "data", "connectionId": "dc_" + rid(), "metadata": {"ring": 1}})
        await asyncio.sleep(2)
        # V5: typ "data", brak sdp
        await S[4].send("OFFER", B.id, {"type": "data", "connectionId": "dc_" + rid(), "metadata": {"ring": 1}})
        await asyncio.sleep(2)
        for k in range(5): log(f"S{k} alive", S[k].closed is None)
        A = S[5]
        # T4: nieistniejący odbiorca -> EXPIRE
        await A.send("OFFER", "tnw-probe-none-" + rid(), {"sdp": SDP, "type": "media", "connectionId": "mc_" + rid()})
        await asyncio.sleep(8)
        # T5: przejęcie identyfikatora tym samym tokenem
        B2 = await Conn("B2", B.id, B.tok).open()
        await asyncio.sleep(2)
        await A.send("OFFER", B.id, {"sdp": SDP, "type": "media", "connectionId": "mc_take"})
        await asyncio.sleep(2.5)
        # T7: wiadomość do id, które pojawi się za 3 s
        late = "tnw-probe-" + rid()
        await A.send("OFFER", late, {"sdp": SDP, "type": "media", "connectionId": "mc_late"})
        await asyncio.sleep(3)
        L = await Conn("L", late, rid(12)).open()
        await asyncio.sleep(5)
        log("A alive", A.closed is None)
    except Exception:
        log("EXC", traceback.format_exc()[-400:])
    text = " | ".join(x[:110] for x in LOG)
    for k in range(0, min(len(text), 8000), 800):
        print(f"::notice title=p{k//800:02d}::{text[k:k+800]}", flush=True)

asyncio.run(main())
