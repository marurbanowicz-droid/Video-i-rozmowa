import asyncio, json, random, string, time, traceback
import websockets

def rid(n=10): return ''.join(random.choice(string.ascii_lowercase+string.digits) for _ in range(n))
def url(i, t): return f"wss://0.peerjs.com:443/peerjs?key=peerjs&id={i}&token={t}&version=1.5.4"
def note(k, v): print(f"::notice title={k}::{str(v)[:900]}", flush=True)

async def recv_all(ws, secs):
    out = []
    end = time.time() + secs
    while True:
        left = end - time.time()
        if left <= 0: break
        try:
            m = await asyncio.wait_for(ws.recv(), left)
            out.append(m[:160])
        except asyncio.TimeoutError: break
        except Exception as e:
            out.append("CLOSED:" + repr(e)[:120]); break
    return out

async def send(ws, dst, cid):
    try:
        await ws.send(json.dumps({"type": "OFFER", "dst": dst, "payload": {"type": "ring", "connectionId": cid}})); return "sent"
    except Exception as e: return "SEND-FAIL " + repr(e)[:100]

async def step(name, coro):
    try: note(name, await coro)
    except Exception as e: note(name, "EXC " + traceback.format_exc()[-300:])

async def main():
    i = "tnw-probe-" + rid(); a = rid(12); b = rid(12)
    j = "tnw-probe-" + rid()
    wsS = await websockets.connect(url(j, rid(12)), ping_interval=None)      # nadawca
    await step("0-sender", recv_all(wsS, 2))
    ws1 = await websockets.connect(url(i, a), ping_interval=None)
    await step("1-fresh", recv_all(ws1, 2))
    await step("1b-basic-routing-send", send(wsS, i, "c1"))
    await step("1c-basic-routing-recv", recv_all(ws1, 3))
    ws2 = await websockets.connect(url(i, a), ping_interval=None)
    await step("3-same-token-new", recv_all(ws2, 3))
    await step("3b-old-socket", recv_all(ws1, 2))
    await step("5-send", send(wsS, i, "c2"))
    await step("5b-new-socket-recv", recv_all(ws2, 4))
    await step("5c-sender-recv", recv_all(wsS, 7))
    try: await ws2.close()
    except Exception: pass
    await asyncio.sleep(1.0)
    ws5 = await websockets.connect(url(i, a), ping_interval=None)
    await step("7-reconnect-after-close", recv_all(ws5, 3))
    await step("7b-send", send(wsS, i, "c3"))
    await step("7c-recv", recv_all(ws5, 3))
    t = time.time()
    await step("8-send-nonexistent", send(wsS, "tnw-probe-none-" + rid(), "c4"))
    await step("8b-expire", recv_all(wsS, 12))
    note("8c-time", f"{time.time()-t:.1f}s")
    # 9. brak heartbeat: po ilu sekundach serwer zamknie gniazdo
    ws6 = await websockets.connect(url("tnw-probe-" + rid(), rid(12)), ping_interval=None)
    t = time.time()
    r = await recv_all(ws6, 100)
    note("9-no-heartbeat", f"{r} after {time.time()-t:.0f}s")

asyncio.run(main())
