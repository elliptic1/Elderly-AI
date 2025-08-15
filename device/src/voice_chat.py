"""Simple streaming voice chat with OpenAI's realtime API.

This script captures audio from the system microphone, streams it to the OpenAI
realtime API, and plays back the assistant's audio responses through the
speakers.  It also injects example side prompts at the beginning and then every
minute to demonstrate how text instructions can steer the conversation while the
streaming session is active.

Requirements:
    * PyAudio for audio capture/playback
    * websockets for realtime communication
    * An environment variable ``OPENAI_API_KEY`` with a valid key

Usage:
    python -m device.src.voice_chat
"""

from __future__ import annotations

import asyncio
import base64
import json
import os
from typing import List

import pyaudio
import websockets

# Audio configuration
RATE = 16_000
CHUNK = 1024

# Example side prompts that steer the conversation.
SIDE_PROMPTS: List[str] = [
    "You are a cheerful tour guide helping someone explore a new city.",
    "Switch personas: respond like an enthusiastic robot.",
    "Change again: give philosophical advice in the style of Shakespeare.",
]


async def send_microphone_audio(ws: websockets.WebSocketClientProtocol) -> None:
    """Capture microphone audio and stream it to the API."""
    audio = pyaudio.PyAudio()
    stream = audio.open(
        format=pyaudio.paInt16,
        channels=1,
        rate=RATE,
        input=True,
        frames_per_buffer=CHUNK,
    )

    try:
        while True:
            data = stream.read(CHUNK, exception_on_overflow=False)
            b64 = base64.b64encode(data).decode("utf-8")
            await ws.send(json.dumps({"type": "input_audio_buffer.append", "audio": b64}))
            await ws.send(json.dumps({"type": "input_audio_buffer.commit"}))
    finally:
        stream.stop_stream()
        stream.close()
        audio.terminate()


def _play_chunk(player: pyaudio.Stream, chunk_b64: str) -> None:
    """Decode a base64 encoded audio chunk and play it."""
    player.write(base64.b64decode(chunk_b64))


async def play_speaker_audio(ws: websockets.WebSocketClientProtocol) -> None:
    """Play audio received from the assistant through the speaker."""
    audio = pyaudio.PyAudio()
    stream = audio.open(
        format=pyaudio.paInt16,
        channels=1,
        rate=RATE,
        output=True,
        frames_per_buffer=CHUNK,
    )

    try:
        async for message in ws:
            event = json.loads(message)
            if event.get("type") == "response.output_audio.delta":
                _play_chunk(stream, event["delta"])
    finally:
        stream.stop_stream()
        stream.close()
        audio.terminate()


async def inject_side_prompts(ws: websockets.WebSocketClientProtocol) -> None:
    """Send side prompts that steer the conversation every minute."""
    for prompt in SIDE_PROMPTS:
        await ws.send(
            json.dumps(
                {
                    "type": "conversation.item.create",
                    "item": {
                        "type": "message",
                        "role": "system",
                        "content": [{"type": "input_text", "text": prompt}],
                    },
                }
            )
        )
        # Ask the model to generate a response based on the latest instruction
        await ws.send(json.dumps({"type": "response.create"}))
        await asyncio.sleep(60)


async def main() -> None:
    """Entry point for establishing the realtime session."""
    api_key = os.environ.get("OPENAI_API_KEY")
    if not api_key:
        raise RuntimeError("OPENAI_API_KEY environment variable is not set")

    uri = "wss://api.openai.com/v1/realtime?model=gpt-4o-realtime-preview"
    headers = {
        "Authorization": f"Bearer {api_key}",
        "OpenAI-Beta": "realtime=v1",
    }

    async with websockets.connect(uri, extra_headers=headers) as ws:
        # Start streaming tasks concurrently.
        await asyncio.gather(
            send_microphone_audio(ws),
            play_speaker_audio(ws),
            inject_side_prompts(ws),
        )


if __name__ == "__main__":
    asyncio.run(main())
