import asyncio, json, random, string, time
import websockets

def rid(n=10): return ''.join(random.choice(string.ascii_lowercase+string.digits) for _ in range(n))
def url(i, t): return f"wss://0.peerjs.com:443/peerjs?key=peerjs&id={i}&token={t}&version=1.5.4"
def note(k, v): print(f"::notice title={k}::{v}", flush=True)

async def recv_all(ws, secs):
    out = []
    end = time.time() + secs
    while True:
        left = end - time.time()
        if left <= 0: break
        try:
            m = await asyncio.wait_for(ws.recv(), left)
            out.append(json.loads(m).get("type"))
        except asyncio.TimeoutError: break
        except Exception as e:
            out.append("CLOSED:" + type(e).__name__); break
    return out

async def main():
    i = "tnw-probe-" + rid(); a = rid(12); b = rid(12)
    ws1 = await websockets.connect(url(i, a), ping_interval=None)
    note("1-fresh", await recv_all(ws1, 3))
    t = time.time()
    try:
        pw = await ws1.ping(); await asyncio.wait_for(pw, 8)
        note("2-ping", f"pong after {time.time()-t:.2f}s")
    except Exception as e: note("2-ping", "NO PONG " + repr(e))
    ws2 = await websockets.connect(url(i, a), ping_interval=None)
    note("3-same-token-while-open", await recv_all(ws2, 4))
    ws3 = await websockets.connect(url(i, b), ping_interval=None)
    note("4-other-token", await recv_all(ws3, 4))
    j = "tnw-probe-" + rid()
    ws4 = await websockets.connect(url(j, rid(12)), ping_interval=None)
    await recv_all(ws4, 2)
    await ws4.send(json.dumps({"type": "OFFER", "dst": i, "payload": {"type": "ring", "connectionId": "x"}}))
    r2, r1 = await asyncio.gather(recv_all(ws2, 3), recv_all(ws1, 3))
    note("5-routing", f"new socket got {r2}; old socket got {r1}")
    await ws1.close()
    await asyncio.sleep(1.5)
    await ws4.send(json.dumps({"type": "OFFER", "dst": i, "payload": {"type": "ring", "connectionId": "y"}}))
    note("6-after-old-close", f"new socket got {await recv_all(ws2, 3)}; sender got {await recv_all(ws4, 7)}")
    await ws2.close()
    await asyncio.sleep(1.0)
    ws5 = await websockets.connect(url(i, a), ping_interval=None)
    note("7-reconnect-after-close", await recv_all(ws5, 3))
    t = time.time()
    await ws4.send(json.dumps({"type": "OFFER", "dst": "tnw-probe-none-" + rid(), "payload": {"type": "ring", "connectionId": "z"}}))
    r = await recv_all(ws4, 12)
    note("8-expire", f"{r} after {time.time()-t:.1f}s")
    for w in (ws3, ws4, ws5):
        try: await w.close()
        except Exception: pass

asyncio.run(main())
