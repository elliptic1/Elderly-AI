"""LED controller for ReSpeaker 2-Mic HAT status indication.

The ReSpeaker 2-Mic HAT has 3 APA102 RGB LEDs controlled via SPI.
This module provides status indication during voice sessions.

If running on non-Pi hardware or without the HAT, this gracefully
falls back to no-op operations.
"""

from __future__ import annotations

import threading
import time
from dataclasses import dataclass
from enum import Enum
from typing import Tuple

import structlog

logger = structlog.get_logger()

# Try to import SPI library (only works on Pi with ReSpeaker)
try:
    import spidev
    SPI_AVAILABLE = True
except ImportError:
    SPI_AVAILABLE = False
    logger.info("spidev not available, LED control disabled")


class DeviceState(Enum):
    """Device states with corresponding LED patterns."""
    OFF = "off"
    IDLE = "idle"           # Dim blue pulse
    LISTENING = "listening"  # Green pulse
    SPEAKING = "speaking"    # Blue solid
    PROCESSING = "processing"  # Yellow spin
    ERROR = "error"          # Red flash
    ALERT = "alert"          # Red pulse


@dataclass
class Color:
    """RGB color."""
    r: int
    g: int
    b: int
    
    def dim(self, factor: float) -> "Color":
        """Return a dimmed version of this color."""
        return Color(
            r=int(self.r * factor),
            g=int(self.g * factor),
            b=int(self.b * factor),
        )
    
    def to_tuple(self) -> Tuple[int, int, int]:
        return (self.r, self.g, self.b)


# Color definitions
COLORS = {
    "off": Color(0, 0, 0),
    "blue": Color(0, 0, 255),
    "green": Color(0, 255, 0),
    "red": Color(255, 0, 0),
    "yellow": Color(255, 200, 0),
    "white": Color(255, 255, 255),
}


class LedController:
    """Controls the ReSpeaker 2-Mic HAT LEDs.
    
    Provides visual feedback for device state:
    - Idle: Dim blue breathing
    - Listening: Green pulsing
    - Speaking: Solid blue
    - Processing: Yellow spinning
    - Error: Red flashing
    - Alert: Red pulsing
    """
    
    NUM_LEDS = 3
    
    def __init__(self):
        self._spi = None
        self._state = DeviceState.OFF
        self._running = False
        self._thread: threading.Thread | None = None
        self._lock = threading.Lock()
        
        if SPI_AVAILABLE:
            try:
                self._spi = spidev.SpiDev()
                self._spi.open(0, 0)
                self._spi.max_speed_hz = 8000000
                logger.info("LED controller initialized")
            except Exception as e:
                logger.warning("Failed to initialize SPI for LEDs", error=str(e))
                self._spi = None
    
    def start(self) -> None:
        """Start the LED animation thread."""
        if self._running:
            return
        
        self._running = True
        self._thread = threading.Thread(target=self._animation_loop, daemon=True)
        self._thread.start()
    
    def stop(self) -> None:
        """Stop the LED animation thread and turn off LEDs."""
        self._running = False
        if self._thread:
            self._thread.join(timeout=1.0)
            self._thread = None
        self.set_state(DeviceState.OFF)
    
    def set_state(self, state: DeviceState) -> None:
        """Set the current device state for LED indication."""
        with self._lock:
            self._state = state
        
        if state == DeviceState.OFF:
            self._set_all_leds(COLORS["off"])
    
    def _animation_loop(self) -> None:
        """Background thread for LED animations."""
        frame = 0
        while self._running:
            with self._lock:
                state = self._state
            
            self._render_frame(state, frame)
            frame = (frame + 1) % 100
            time.sleep(0.05)  # 20 FPS
    
    def _render_frame(self, state: DeviceState, frame: int) -> None:
        """Render a single animation frame based on state."""
        match state:
            case DeviceState.OFF:
                pass  # Already off
            
            case DeviceState.IDLE:
                # Dim blue breathing
                brightness = 0.1 + 0.1 * abs((frame % 50) - 25) / 25
                self._set_all_leds(COLORS["blue"].dim(brightness))
            
            case DeviceState.LISTENING:
                # Green pulsing
                brightness = 0.3 + 0.7 * abs((frame % 40) - 20) / 20
                self._set_all_leds(COLORS["green"].dim(brightness))
            
            case DeviceState.SPEAKING:
                # Solid blue
                self._set_all_leds(COLORS["blue"].dim(0.8))
            
            case DeviceState.PROCESSING:
                # Yellow spinning
                active_led = (frame // 10) % self.NUM_LEDS
                for i in range(self.NUM_LEDS):
                    if i == active_led:
                        self._set_led(i, COLORS["yellow"])
                    else:
                        self._set_led(i, COLORS["yellow"].dim(0.1))
            
            case DeviceState.ERROR:
                # Red flashing
                if (frame // 10) % 2 == 0:
                    self._set_all_leds(COLORS["red"])
                else:
                    self._set_all_leds(COLORS["off"])
            
            case DeviceState.ALERT:
                # Red pulsing (similar to listening but red)
                brightness = 0.3 + 0.7 * abs((frame % 30) - 15) / 15
                self._set_all_leds(COLORS["red"].dim(brightness))
    
    def _set_all_leds(self, color: Color) -> None:
        """Set all LEDs to the same color."""
        for i in range(self.NUM_LEDS):
            self._set_led(i, color)
        self._flush()
    
    def _set_led(self, index: int, color: Color) -> None:
        """Set a specific LED color (buffered, call _flush to apply)."""
        if not hasattr(self, "_led_buffer"):
            self._led_buffer = [(0, 0, 0)] * self.NUM_LEDS
        
        if 0 <= index < self.NUM_LEDS:
            self._led_buffer[index] = color.to_tuple()
    
    def _flush(self) -> None:
        """Send the LED buffer to the hardware."""
        if not self._spi or not hasattr(self, "_led_buffer"):
            return
        
        # APA102 protocol: start frame + LED frames + end frame
        # Start frame: 4 bytes of 0x00
        # LED frame: 0xE0 | brightness, blue, green, red
        # End frame: 4 bytes of 0xFF
        
        data = [0x00] * 4  # Start frame
        
        for r, g, b in self._led_buffer:
            brightness = 31  # Max brightness for APA102 (5 bits)
            data.extend([0xE0 | brightness, b, g, r])
        
        data.extend([0xFF] * 4)  # End frame
        
        try:
            self._spi.xfer2(data)
        except Exception as e:
            logger.error("Failed to write to LEDs", error=str(e))
    
    def __del__(self):
        """Cleanup SPI on deletion."""
        if self._spi:
            try:
                self._set_all_leds(COLORS["off"])
                self._spi.close()
            except Exception:
                pass


# Singleton instance for easy access
_controller: LedController | None = None


def get_led_controller() -> LedController:
    """Get the global LED controller instance."""
    global _controller
    if _controller is None:
        _controller = LedController()
    return _controller


def set_device_state(state: DeviceState) -> None:
    """Convenience function to set device state."""
    get_led_controller().set_state(state)
