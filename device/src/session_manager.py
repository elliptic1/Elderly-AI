"""Session manager that orchestrates voice check-in sessions."""

from __future__ import annotations

import asyncio
import json
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Callable

import structlog

from .audio_manager import AudioManager
from .config import (
    DeviceConfig,
    MAX_SESSION_DURATION_SEC,
    SILENCE_TIMEOUT_SEC,
    SESSIONS_DIR,
    ALERTS_DIR,
)
from .led_controller import DeviceState, get_led_controller
from .models import Schedule
from .realtime_client import (
    OpenAIRealtimeClient,
    RealtimeListener,
    SessionResult,
)
from .session_analyzer import (
    AnalysisAction,
    AnalysisResult,
    SessionAnalyzer,
    quick_analyze,
)

logger = structlog.get_logger()


@dataclass
class SessionState:
    """Tracks the state of an active session."""
    schedule_id: str
    start_time: float = field(default_factory=time.time)
    last_audio_time: float = field(default_factory=time.time)
    user_transcript: list[str] = field(default_factory=list)
    assistant_transcript: list[str] = field(default_factory=list)
    is_active: bool = True
    is_speaking: bool = False  # True when AI is speaking


class VoiceSessionListener(RealtimeListener):
    """Handles events from the OpenAI Realtime API."""
    
    def __init__(
        self,
        session_state: SessionState,
        audio_manager: AudioManager,
        on_session_end: Callable[[SessionResult], None],
    ):
        self.state = session_state
        self.audio = audio_manager
        self.on_session_end = on_session_end
        self.led = get_led_controller()
    
    def on_connected(self) -> None:
        logger.info("Voice session connected")
        self.led.set_state(DeviceState.LISTENING)
    
    def on_audio_received(self, audio_data: bytes) -> None:
        self.audio.play_audio(audio_data)
    
    def on_speech_started(self) -> None:
        logger.debug("Assistant started speaking")
        self.state.is_speaking = True
        self.audio.pause_recording()  # Avoid feedback
        self.led.set_state(DeviceState.SPEAKING)
    
    def on_speech_ended(self) -> None:
        logger.debug("Assistant finished speaking")
        self.state.is_speaking = False
        self.state.last_audio_time = time.time()
        self.audio.resume_recording()
        self.led.set_state(DeviceState.LISTENING)
    
    def on_user_transcript(self, transcript: str) -> None:
        logger.info("User said", transcript=transcript)
        self.state.user_transcript.append(transcript)
        self.state.last_audio_time = time.time()
    
    def on_assistant_transcript(self, transcript: str) -> None:
        self.state.assistant_transcript.append(transcript)
    
    def on_session_ended(self, result: SessionResult) -> None:
        self.on_session_end(result)
    
    def on_error(self, error: str) -> None:
        logger.error("Realtime API error", error=error)
        self.led.set_state(DeviceState.ERROR)


class SessionManager:
    """Manages the lifecycle of voice check-in sessions.
    
    Coordinates:
    - Audio capture/playback
    - OpenAI Realtime API connection
    - Session timeouts (max duration, silence)
    - Transcript collection
    - Session analysis and caregiver alerts
    - LED status indication
    """
    
    def __init__(self, config: DeviceConfig | None = None):
        self.config = config or DeviceConfig.load()
        self.logger = structlog.get_logger()
        self.led = get_led_controller()
        
        self._current_session: SessionState | None = None
        self._audio_manager: AudioManager | None = None
        self._realtime_client: OpenAIRealtimeClient | None = None
        self._timeout_thread: threading.Thread | None = None
        self._loop: asyncio.AbstractEventLoop | None = None
    
    def start_session(self, schedule: Schedule) -> None:
        """Start a new voice check-in session.
        
        Args:
            schedule: The schedule that triggered this session.
        """
        if self._current_session and self._current_session.is_active:
            self.logger.warning("Session already active, ignoring")
            return
        
        self.logger.info("Starting voice session", schedule_id=schedule.id)
        
        # Create session state
        self._current_session = SessionState(schedule_id=schedule.id)
        
        # Ensure directories exist
        SESSIONS_DIR.mkdir(parents=True, exist_ok=True)
        ALERTS_DIR.mkdir(parents=True, exist_ok=True)
        
        # Start LED controller
        self.led.start()
        self.led.set_state(DeviceState.PROCESSING)
        
        # Start the async session in a new thread
        self._session_thread = threading.Thread(
            target=self._run_session_async,
            daemon=True,
        )
        self._session_thread.start()
    
    def _run_session_async(self) -> None:
        """Run the async session in a new event loop."""
        self._loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self._loop)
        
        try:
            self._loop.run_until_complete(self._session_main())
        except Exception as e:
            self.logger.error("Session error", error=str(e))
        finally:
            self._loop.close()
            self._loop = None
    
    async def _session_main(self) -> None:
        """Main async session logic."""
        session = self._current_session
        if not session:
            return
        
        # Set up audio manager with callback
        self._audio_manager = AudioManager(on_audio_captured=self._on_audio_captured)
        
        # Set up realtime client
        listener = VoiceSessionListener(
            session_state=session,
            audio_manager=self._audio_manager,
            on_session_end=self._on_session_end_sync,
        )
        
        self._realtime_client = OpenAIRealtimeClient(
            api_key=self.config.openai_api_key,
            listener=listener,
        )
        
        # Start timeout monitoring
        self._start_timeout_monitor()
        
        # Start audio
        self._audio_manager.start_recording()
        self._audio_manager.start_playback()
        
        # Connect and run until session ends
        try:
            await self._realtime_client.connect()
        except Exception as e:
            self.logger.error("Realtime connection failed", error=str(e))
            self.led.set_state(DeviceState.ERROR)
        finally:
            self._cleanup()
    
    def _on_audio_captured(self, audio_data: bytes) -> None:
        """Handle captured audio from microphone."""
        if self._current_session:
            self._current_session.last_audio_time = time.time()
        
        if self._realtime_client and self._loop:
            # Send audio to the API
            asyncio.run_coroutine_threadsafe(
                self._realtime_client.send_audio(audio_data),
                self._loop,
            )
    
    def _start_timeout_monitor(self) -> None:
        """Start the timeout monitoring thread."""
        self._timeout_thread = threading.Thread(
            target=self._timeout_monitor_loop,
            daemon=True,
        )
        self._timeout_thread.start()
    
    def _timeout_monitor_loop(self) -> None:
        """Monitor for session and silence timeouts."""
        while self._current_session and self._current_session.is_active:
            session = self._current_session
            now = time.time()
            
            # Check max session duration
            if now - session.start_time > MAX_SESSION_DURATION_SEC:
                self.logger.info("Session timeout reached")
                self._end_session("Session timed out")
                return
            
            # Check silence timeout (only when not speaking)
            if not session.is_speaking:
                if now - session.last_audio_time > SILENCE_TIMEOUT_SEC:
                    self.logger.info("Silence timeout, committing audio")
                    if self._realtime_client and self._loop:
                        asyncio.run_coroutine_threadsafe(
                            self._realtime_client.commit_audio(),
                            self._loop,
                        )
                    # Reset timeout
                    session.last_audio_time = now
            
            time.sleep(1.0)
    
    def _on_session_end_sync(self, result: SessionResult) -> None:
        """Handle session end (called from listener)."""
        self._end_session("Session completed")
    
    def _end_session(self, reason: str) -> None:
        """End the current session and perform analysis."""
        session = self._current_session
        if not session or not session.is_active:
            return
        
        session.is_active = False
        self.logger.info("Ending session", reason=reason)
        
        # Get transcripts
        user_text = " ".join(session.user_transcript)
        assistant_text = " ".join(session.assistant_transcript)
        
        # Quick analysis for immediate response
        quick_result = quick_analyze(user_text)
        self.logger.info(
            "Quick analysis",
            action=quick_result.action.value,
            urgency=quick_result.urgency_level,
            summary=quick_result.summary,
        )
        
        # Handle result
        self._handle_analysis_result(session, quick_result, user_text, assistant_text)
        
        # Deep analysis in background (for logging/learning)
        threading.Thread(
            target=self._run_deep_analysis,
            args=(session, user_text, assistant_text, quick_result),
            daemon=True,
        ).start()
        
        # Cleanup will happen after disconnect
        if self._realtime_client and self._loop:
            asyncio.run_coroutine_threadsafe(
                self._realtime_client.disconnect(),
                self._loop,
            )
    
    def _run_deep_analysis(
        self,
        session: SessionState,
        user_text: str,
        assistant_text: str,
        quick_result: AnalysisResult,
    ) -> None:
        """Run deep analysis in background."""
        try:
            loop = asyncio.new_event_loop()
            analyzer = SessionAnalyzer(self.config.openai_api_key)
            
            deep_result = loop.run_until_complete(
                analyzer.analyze_async(user_text, assistant_text)
            )
            
            self.logger.info(
                "Deep analysis complete",
                action=deep_result.action.value,
                urgency=deep_result.urgency_level,
                summary=deep_result.summary,
            )
            
            # If deep analysis finds something quick missed, alert
            if (
                deep_result.action == AnalysisAction.ALERT_CAREGIVER
                and quick_result.action != AnalysisAction.ALERT_CAREGIVER
            ):
                self.logger.warning("Deep analysis found issue missed by quick analysis!")
                self._send_caregiver_alert(deep_result, user_text)
            
            loop.close()
            
        except Exception as e:
            self.logger.error("Deep analysis failed", error=str(e))
    
    def _handle_analysis_result(
        self,
        session: SessionState,
        result: AnalysisResult,
        user_text: str,
        assistant_text: str,
    ) -> None:
        """Handle the analysis result."""
        # Log session
        self._log_session(session, result, user_text, assistant_text)
        
        # Handle action
        match result.action:
            case AnalysisAction.ALERT_CAREGIVER:
                self._send_caregiver_alert(result, user_text)
                self.led.set_state(DeviceState.ALERT)
            
            case AnalysisAction.CONTINUE:
                self.logger.info("Person wanted to continue talking")
                self.led.set_state(DeviceState.IDLE)
            
            case AnalysisAction.NONE:
                self.logger.info("Session completed normally")
                self.led.set_state(DeviceState.IDLE)
    
    def _send_caregiver_alert(self, result: AnalysisResult, user_text: str) -> None:
        """Send alert to caregiver."""
        self.logger.warning(
            "ALERT: Sending caregiver notification!",
            urgency=result.urgency_level,
            summary=result.summary,
        )
        
        # Log alert to file
        timestamp = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
        alert_file = ALERTS_DIR / f"alert_{timestamp}.json"
        
        alert_data = {
            "timestamp": datetime.now().isoformat(),
            "device_id": self.config.device_id,
            "urgency_level": result.urgency_level,
            "summary": result.summary,
            "reasoning": result.reasoning,
            "user_said": user_text,
        }
        
        with open(alert_file, "w") as f:
            json.dump(alert_data, f, indent=2)
        
        self.logger.info("Alert logged", file=str(alert_file))
        
        # TODO: Send via Firebase Cloud Messaging to caregiver app
    
    def _log_session(
        self,
        session: SessionState,
        result: AnalysisResult,
        user_text: str,
        assistant_text: str,
    ) -> None:
        """Log session details to file."""
        timestamp = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
        session_file = SESSIONS_DIR / f"session_{timestamp}.json"
        
        duration_sec = time.time() - session.start_time
        
        session_data = {
            "timestamp": datetime.now().isoformat(),
            "device_id": self.config.device_id,
            "schedule_id": session.schedule_id,
            "duration_seconds": int(duration_sec),
            "analysis": result.to_dict(),
            "user_transcript": user_text,
            "assistant_transcript": assistant_text,
        }
        
        with open(session_file, "w") as f:
            json.dump(session_data, f, indent=2)
        
        self.logger.info("Session logged", file=str(session_file))
    
    def _cleanup(self) -> None:
        """Clean up resources after session ends."""
        if self._audio_manager:
            self._audio_manager.release()
            self._audio_manager = None
        
        self._realtime_client = None
        self._current_session = None
        
        # Keep LED in idle state
        self.led.set_state(DeviceState.IDLE)
        
        self.logger.info("Session cleanup complete")
    
    def stop(self) -> None:
        """Force stop any active session."""
        if self._current_session:
            self._end_session("Forced stop")
        self.led.stop()
