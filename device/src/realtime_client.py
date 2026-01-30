"""OpenAI Realtime API WebSocket client for voice conversations."""

from __future__ import annotations

import asyncio
import base64
import json
from dataclasses import dataclass, field
from enum import Enum
from typing import Callable, Protocol

import structlog
import websockets
from websockets.client import WebSocketClientProtocol

from .config import OPENAI_REALTIME_URL, ELDERCARE_SYSTEM_PROMPT

logger = structlog.get_logger()


class SessionAction(Enum):
    """Follow-up action after session ends."""
    NONE = "none"
    CONTINUE = "continue"
    ALERT_CAREGIVER = "alert_caregiver"


@dataclass
class SessionResult:
    """Result of a completed voice session."""
    action: SessionAction = SessionAction.NONE
    summary: str = ""
    user_transcript: str = ""
    assistant_transcript: str = ""


class RealtimeListener(Protocol):
    """Callback interface for realtime events."""
    
    def on_connected(self) -> None:
        """Called when WebSocket connection is established."""
        ...
    
    def on_audio_received(self, audio_data: bytes) -> None:
        """Called when audio is received from the assistant."""
        ...
    
    def on_speech_started(self) -> None:
        """Called when the assistant starts speaking."""
        ...
    
    def on_speech_ended(self) -> None:
        """Called when the assistant stops speaking."""
        ...
    
    def on_user_transcript(self, transcript: str) -> None:
        """Called when user speech is transcribed."""
        ...
    
    def on_assistant_transcript(self, transcript: str) -> None:
        """Called when assistant speech is transcribed."""
        ...
    
    def on_session_ended(self, result: SessionResult) -> None:
        """Called when the session ends."""
        ...
    
    def on_error(self, error: str) -> None:
        """Called on error."""
        ...


@dataclass
class OpenAIRealtimeClient:
    """Client for OpenAI's Realtime Voice API.
    
    Handles:
    - WebSocket connection management
    - Sending audio input (16kHz mono PCM)
    - Receiving audio output
    - Session configuration with eldercare prompt
    - Transcript collection
    """
    
    api_key: str
    listener: RealtimeListener | None = None
    system_prompt: str = ELDERCARE_SYSTEM_PROMPT
    voice: str = "alloy"
    
    _ws: WebSocketClientProtocol | None = field(default=None, init=False, repr=False)
    _connected: bool = field(default=False, init=False)
    _running: bool = field(default=False, init=False)
    _user_transcript: list[str] = field(default_factory=list, init=False)
    _assistant_transcript: list[str] = field(default_factory=list, init=False)
    
    async def connect(self) -> None:
        """Connect to the OpenAI Realtime API."""
        if self._connected:
            return
        
        logger.info("Connecting to OpenAI Realtime API")
        self._running = True
        
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "OpenAI-Beta": "realtime=v1",
        }
        
        try:
            self._ws = await websockets.connect(
                OPENAI_REALTIME_URL,
                additional_headers=headers,
                ping_interval=30,
                ping_timeout=10,
            )
            self._connected = True
            logger.info("WebSocket connected")
            
            # Configure session
            await self._configure_session()
            
            # Notify listener
            if self.listener:
                self.listener.on_connected()
            
            # Trigger initial greeting
            await self._trigger_initial_greeting()
            
            # Start receiving messages
            await self._receive_loop()
            
        except Exception as e:
            logger.error("Connection failed", error=str(e))
            if self.listener:
                self.listener.on_error(f"Connection failed: {e}")
    
    async def _configure_session(self) -> None:
        """Send session configuration to the API."""
        config = {
            "type": "session.update",
            "session": {
                "modalities": ["text", "audio"],
                "instructions": self.system_prompt,
                "voice": self.voice,
                "input_audio_format": "pcm16",
                "output_audio_format": "pcm16",
                "input_audio_transcription": {
                    "model": "whisper-1",
                },
                "turn_detection": {
                    "type": "server_vad",
                    "threshold": 0.5,
                    "prefix_padding_ms": 300,
                    "silence_duration_ms": 500,
                },
            },
        }
        await self._send(config)
        logger.info("Session configured")
    
    async def _trigger_initial_greeting(self) -> None:
        """Trigger the AI to start the conversation with a greeting."""
        # Add a context message to prompt greeting
        context_msg = {
            "type": "conversation.item.create",
            "item": {
                "type": "message",
                "role": "user",
                "content": [
                    {
                        "type": "input_text",
                        "text": "Please start the check-in by greeting me warmly.",
                    }
                ],
            },
        }
        await self._send(context_msg)
        
        # Request response
        await self._send({"type": "response.create"})
        logger.info("Initial greeting triggered")
    
    async def send_audio(self, audio_data: bytes) -> None:
        """Send audio data to the API.
        
        Args:
            audio_data: Raw 16-bit 16kHz mono PCM audio.
        """
        if not self._connected or not self._ws:
            return
        
        base64_audio = base64.b64encode(audio_data).decode("utf-8")
        await self._send({
            "type": "input_audio_buffer.append",
            "audio": base64_audio,
        })
    
    async def commit_audio(self) -> None:
        """Commit the audio buffer and request a response."""
        if not self._connected or not self._ws:
            return
        
        await self._send({"type": "input_audio_buffer.commit"})
        await self._send({"type": "response.create"})
    
    async def disconnect(self) -> None:
        """Disconnect from the API."""
        self._running = False
        self._connected = False
        
        if self._ws:
            await self._ws.close()
            self._ws = None
        
        logger.info("Disconnected from OpenAI Realtime API")
    
    def get_session_result(self) -> SessionResult:
        """Get the session result with collected transcripts."""
        return SessionResult(
            user_transcript=" ".join(self._user_transcript),
            assistant_transcript=" ".join(self._assistant_transcript),
        )
    
    async def _send(self, message: dict) -> None:
        """Send a JSON message to the WebSocket."""
        if self._ws:
            await self._ws.send(json.dumps(message))
    
    async def _receive_loop(self) -> None:
        """Receive and handle messages from the WebSocket."""
        if not self._ws:
            return
        
        try:
            async for message in self._ws:
                if not self._running:
                    break
                
                event = json.loads(message)
                await self._handle_event(event)
                
        except websockets.exceptions.ConnectionClosed:
            logger.info("WebSocket connection closed")
        except Exception as e:
            logger.error("Receive error", error=str(e))
            if self.listener:
                self.listener.on_error(f"Connection lost: {e}")
    
    async def _handle_event(self, event: dict) -> None:
        """Handle an incoming event from the API."""
        event_type = event.get("type", "")
        
        match event_type:
            case "session.created":
                logger.info("Session created")
            
            case "session.updated":
                logger.info("Session updated")
            
            case "response.audio.delta":
                # Audio chunk received
                audio_b64 = event.get("delta", "")
                if audio_b64 and self.listener:
                    audio_data = base64.b64decode(audio_b64)
                    self.listener.on_audio_received(audio_data)
            
            case "response.audio_transcript.delta":
                # Assistant transcript chunk
                text = event.get("delta", "")
                if text:
                    self._assistant_transcript.append(text)
                    if self.listener:
                        self.listener.on_assistant_transcript(text)
            
            case "conversation.item.input_audio_transcription.completed":
                # User transcript complete
                transcript = event.get("transcript", "")
                if transcript:
                    self._user_transcript.append(transcript)
                    if self.listener:
                        self.listener.on_user_transcript(transcript)
            
            case "response.audio.started":
                if self.listener:
                    self.listener.on_speech_started()
            
            case "response.audio.done":
                if self.listener:
                    self.listener.on_speech_ended()
            
            case "response.done":
                logger.debug("Response complete")
            
            case "input_audio_buffer.speech_started":
                logger.debug("User started speaking")
            
            case "input_audio_buffer.speech_stopped":
                logger.debug("User stopped speaking")
            
            case "error":
                error_obj = event.get("error", {})
                error_msg = error_obj.get("message", "Unknown error")
                logger.error("API error", error=error_msg)
                if self.listener:
                    self.listener.on_error(error_msg)
            
            case _:
                logger.debug("Event received", type=event_type)


class SyncRealtimeClient:
    """Synchronous wrapper for the async OpenAI Realtime client.
    
    Runs the async client in a background thread with its own event loop.
    Use this when integrating with synchronous code.
    """
    
    def __init__(
        self,
        api_key: str,
        listener: RealtimeListener | None = None,
        system_prompt: str = ELDERCARE_SYSTEM_PROMPT,
    ):
        self._client = OpenAIRealtimeClient(
            api_key=api_key,
            listener=listener,
            system_prompt=system_prompt,
        )
        self._loop: asyncio.AbstractEventLoop | None = None
        self._thread: asyncio.Thread | None = None
        self._audio_queue: asyncio.Queue[bytes] = asyncio.Queue()
    
    def connect(self) -> None:
        """Connect to the API (blocking)."""
        import threading
        
        def run_loop():
            self._loop = asyncio.new_event_loop()
            asyncio.set_event_loop(self._loop)
            self._loop.run_until_complete(self._run())
        
        self._thread = threading.Thread(target=run_loop, daemon=True)
        self._thread.start()
    
    async def _run(self) -> None:
        """Run the client and audio sender concurrently."""
        await asyncio.gather(
            self._client.connect(),
            self._audio_sender(),
        )
    
    async def _audio_sender(self) -> None:
        """Send queued audio to the client."""
        while self._client._running:
            try:
                audio = await asyncio.wait_for(self._audio_queue.get(), timeout=0.1)
                await self._client.send_audio(audio)
            except asyncio.TimeoutError:
                continue
    
    def send_audio(self, audio_data: bytes) -> None:
        """Queue audio data to be sent."""
        if self._loop:
            self._loop.call_soon_threadsafe(
                self._audio_queue.put_nowait, audio_data
            )
    
    def commit_audio(self) -> None:
        """Commit audio and request response."""
        if self._loop:
            asyncio.run_coroutine_threadsafe(
                self._client.commit_audio(), self._loop
            )
    
    def disconnect(self) -> None:
        """Disconnect from the API."""
        if self._loop:
            asyncio.run_coroutine_threadsafe(
                self._client.disconnect(), self._loop
            )
    
    def get_session_result(self) -> SessionResult:
        """Get the session result."""
        return self._client.get_session_result()
