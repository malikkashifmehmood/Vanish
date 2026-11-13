"""
A fake classmate, handy when you only have one emulator.
Needs the relay running:  python demo_bot.py [ws://localhost:8000/ws]
Sends a short message every 12 seconds and confirms delivery of what it receives.
"""
import asyncio
import json
import sys
import uuid

import websockets

URL = sys.argv[1] if len(sys.argv) > 1 else "ws://localhost:8000/ws"
DEVICE_ID = "demo-bot-" + uuid.uuid4().hex[:8]


async def main() -> None:
    async with websockets.connect(URL) as ws:
        await ws.send(json.dumps({"type": "hello", "version": 1, "device_id": DEVICE_ID, "name": "ClassBot"}))

        async def talk() -> None:
            n = 0
            while True:
                await asyncio.sleep(12)
                n += 1
                await ws.send(json.dumps({"type": "msg", "id": str(uuid.uuid4()),
                                          "sender": "ClassBot", "text": f"hello from the bot #{n}"}))

        task = asyncio.create_task(talk())
        try:
            async for raw in ws:
                data = json.loads(raw)
                if data.get("type") == "msg":
                    print("received message from", data.get("sender"))
                    await ws.send(json.dumps({"type": "delivered", "id": data["id"]}))
        finally:
            task.cancel()


if __name__ == "__main__":
    asyncio.run(main())
