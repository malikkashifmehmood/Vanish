"""
Vanish relay (local stand-in for the class relay).

Run from this folder:
    uvicorn main:app --host 0.0.0.0 --port 8000
Clients connect to  ws://<this-computer>:8000/ws
(The Android emulator reaches this computer at 10.0.2.2.)

Privacy: this file logs nothing about messages. Keep it that way.
"""
import asyncio
import json
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, WebSocket, WebSocketDisconnect

from relay_core import Out, RelayCore

core = RelayCore()
sockets: dict[str, WebSocket] = {}


async def dispatch(outs: list[Out]) -> None:
    for o in outs:
        ws = sockets.get(o.to)
        if ws is None:
            continue
        try:
            await ws.send_text(json.dumps(o.payload, separators=(",", ":")))
            if o.close:
                await ws.close()
        except Exception:
            pass  # a dead socket is cleaned up by its own receive loop


async def sweeper() -> None:
    while True:
        await asyncio.sleep(0.25)
        await dispatch(core.sweep())


@asynccontextmanager
async def lifespan(_: FastAPI):
    task = asyncio.create_task(sweeper())
    yield
    task.cancel()


app = FastAPI(title="Vanish relay", lifespan=lifespan)


@app.get("/health")
def health():
    return {"status": "ok", "clients": len(sockets)}


@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket):
    await ws.accept()
    conn = uuid.uuid4().hex
    sockets[conn] = ws
    try:
        while True:
            raw = await ws.receive_text()
            try:
                data = json.loads(raw)
            except ValueError:
                data = None
            await dispatch(core.on_message(conn, data))
    except WebSocketDisconnect:
        pass
    except Exception:
        pass
    finally:
        sockets.pop(conn, None)
        core.on_disconnect(conn)
