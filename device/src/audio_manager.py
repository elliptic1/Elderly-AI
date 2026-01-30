"""Audio capture and playback manager using PyAudio."""

from __future__ import annotations

import queue
import threading
from typing import Callable

import pyaudio
import structlog

from .config import AUDIO_RATE, AUDIO_CHANNELS, AUDIO_CHUNK, AUDIO_FORMAT_WIDTH

logger = structlog.get_logger()


class AudioManager:
    """Manages microphone input and speaker output.
    
    Provides:
    - Continuous microphone capture with callback
    - Queued audio playback for responses
    - Thread-safe operation
    """
    
    def __init__(self, on_audio_captured: Callable[[bytes], None] | None = None):
        """Initialize audio manager.
        
        Args:
            on_audio_captured: Callback for captured audio chunks (16kHz mono PCM).
        """
        self._audio = pyaudio.PyAudio()
        self._on_audio_captured = on_audio_captured
        
        self._input_stream: pyaudio.Stream | None = None
        self._output_stream: pyaudio.Stream | None = None
        
        self._playback_queue: queue.Queue[bytes] = queue.Queue()
        self._playback_thread: threading.Thread | None = None
        
        self._recording = False
        self._playing = False
        self._paused = False
        
        self._lock = threading.Lock()
    
    def start_recording(self) -> None:
        """Start capturing audio from the microphone."""
        with self._lock:
            if self._recording:
                return
            
            logger.info("Starting audio recording", rate=AUDIO_RATE, channels=AUDIO_CHANNELS)
            
            self._input_stream = self._audio.open(
                format=pyaudio.paInt16,
                channels=AUDIO_CHANNELS,
                rate=AUDIO_RATE,
                input=True,
                frames_per_buffer=AUDIO_CHUNK,
                stream_callback=self._recording_callback,
            )
            self._recording = True
            self._paused = False
    
    def stop_recording(self) -> None:
        """Stop capturing audio."""
        with self._lock:
            if not self._recording:
                return
            
            logger.info("Stopping audio recording")
            self._recording = False
            
            if self._input_stream:
                self._input_stream.stop_stream()
                self._input_stream.close()
                self._input_stream = None
    
    def pause_recording(self) -> None:
        """Temporarily pause recording (e.g., while AI speaks)."""
        self._paused = True
    
    def resume_recording(self) -> None:
        """Resume recording after pause."""
        self._paused = False
    
    def start_playback(self) -> None:
        """Start the playback thread for audio responses."""
        with self._lock:
            if self._playing:
                return
            
            logger.info("Starting audio playback")
            
            self._output_stream = self._audio.open(
                format=pyaudio.paInt16,
                channels=AUDIO_CHANNELS,
                rate=AUDIO_RATE,
                output=True,
                frames_per_buffer=AUDIO_CHUNK,
            )
            self._playing = True
            
            self._playback_thread = threading.Thread(target=self._playback_loop, daemon=True)
            self._playback_thread.start()
    
    def stop_playback(self) -> None:
        """Stop audio playback."""
        with self._lock:
            if not self._playing:
                return
            
            logger.info("Stopping audio playback")
            self._playing = False
            
            # Clear the queue
            while not self._playback_queue.empty():
                try:
                    self._playback_queue.get_nowait()
                except queue.Empty:
                    break
            
            # Signal thread to exit
            self._playback_queue.put(None)
            
            if self._playback_thread:
                self._playback_thread.join(timeout=1.0)
                self._playback_thread = None
            
            if self._output_stream:
                self._output_stream.stop_stream()
                self._output_stream.close()
                self._output_stream = None
    
    def play_audio(self, audio_data: bytes) -> None:
        """Queue audio data for playback.
        
        Args:
            audio_data: Raw 16-bit 16kHz mono PCM audio.
        """
        if self._playing:
            self._playback_queue.put(audio_data)
    
    def release(self) -> None:
        """Release all audio resources."""
        self.stop_recording()
        self.stop_playback()
        self._audio.terminate()
        logger.info("Audio manager released")
    
    def _recording_callback(
        self,
        in_data: bytes,
        frame_count: int,
        time_info: dict,
        status: int,
    ) -> tuple[None, int]:
        """PyAudio callback for captured audio."""
        if self._recording and not self._paused and self._on_audio_captured:
            self._on_audio_captured(in_data)
        return (None, pyaudio.paContinue)
    
    def _playback_loop(self) -> None:
        """Background thread for audio playback."""
        while self._playing:
            try:
                audio_data = self._playback_queue.get(timeout=0.1)
                if audio_data is None:
                    break
                if self._output_stream and self._playing:
                    self._output_stream.write(audio_data)
            except queue.Empty:
                continue
            except Exception as e:
                logger.error("Playback error", error=str(e))


def list_audio_devices() -> None:
    """Print available audio devices for debugging."""
    audio = pyaudio.PyAudio()
    print("\nAvailable Audio Devices:")
    print("-" * 50)
    
    for i in range(audio.get_device_count()):
        info = audio.get_device_info_by_index(i)
        device_type = []
        if info["maxInputChannels"] > 0:
            device_type.append("INPUT")
        if info["maxOutputChannels"] > 0:
            device_type.append("OUTPUT")
        
        print(f"[{i}] {info['name']}")
        print(f"    Type: {', '.join(device_type)}")
        print(f"    Sample Rate: {int(info['defaultSampleRate'])} Hz")
        print()
    
    audio.terminate()


if __name__ == "__main__":
    list_audio_devices()
