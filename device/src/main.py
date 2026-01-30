"""Main entry point for the ElderCare device agent.

This script runs on a Raspberry Pi with a ReSpeaker 2-Mic HAT.
It handles:
- WiFi provisioning via BLE (if not configured)
- Firebase schedule synchronization
- Voice check-in sessions via OpenAI Realtime API
- Caregiver alerts

Usage:
    python -m device.src.main
    
Environment variables:
    OPENAI_API_KEY: Required. OpenAI API key for Realtime API.
    ELDERCARE_DEVICE_ID: Optional. Device identifier.
    FIREBASE_PROJECT_ID: Optional. Firebase project for sync.
"""

from __future__ import annotations

import asyncio
import signal
import sys
from pathlib import Path

import structlog

# Configure structured logging
structlog.configure(
    processors=[
        structlog.stdlib.add_log_level,
        structlog.stdlib.PositionalArgumentsFormatter(),
        structlog.processors.TimeStamper(fmt="iso"),
        structlog.processors.StackInfoRenderer(),
        structlog.processors.format_exc_info,
        structlog.dev.ConsoleRenderer(colors=True),
    ],
    wrapper_class=structlog.stdlib.BoundLogger,
    context_class=dict,
    logger_factory=structlog.PrintLoggerFactory(),
    cache_logger_on_first_use=True,
)

logger = structlog.get_logger()

from .config import DeviceConfig, DATA_DIR
from .led_controller import DeviceState, get_led_controller
from .models import Schedule
from .session_manager import SessionManager


def is_wifi_configured() -> bool:
    """Check if WiFi is configured on this device."""
    # Check for wpa_supplicant config or NetworkManager connection
    wpa_conf = Path("/etc/wpa_supplicant/wpa_supplicant.conf")
    if wpa_conf.exists():
        content = wpa_conf.read_text()
        if "ssid=" in content:
            return True
    
    # Check NetworkManager (Ubuntu/Raspbian with desktop)
    nm_connections = Path("/etc/NetworkManager/system-connections")
    if nm_connections.exists() and any(nm_connections.iterdir()):
        return True
    
    # For development, assume configured if we have an API key
    try:
        DeviceConfig.load()
        return True
    except ValueError:
        return False


def start_ble_onboarding() -> None:
    """Start BLE onboarding for WiFi provisioning."""
    from . import ble_onboarding
    
    logger.info("Starting BLE onboarding...")
    get_led_controller().start()
    get_led_controller().set_state(DeviceState.IDLE)
    
    asyncio.run(ble_onboarding.start_advertising())


def run_normal_operation(config: DeviceConfig) -> None:
    """Run the normal device operation loop."""
    logger.info("Starting normal operation", device_id=config.device_id)
    
    # Initialize components
    led = get_led_controller()
    led.start()
    led.set_state(DeviceState.IDLE)
    
    session_manager = SessionManager(config)
    
    # Set up signal handlers for graceful shutdown
    def shutdown(signum, frame):
        logger.info("Shutdown signal received")
        session_manager.stop()
        led.stop()
        sys.exit(0)
    
    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)
    
    # Try to connect to Firebase for schedules
    if config.firebase_project_id:
        try:
            from . import firebase_client
            
            if firebase_client.initialize_firebase():
                logger.info("Firebase connected, listening for schedules")
                firebase_client.listen_for_schedules(
                    lambda schedule: session_manager.start_session(schedule)
                )
            else:
                logger.warning("Firebase initialization failed, running in standalone mode")
        except ImportError:
            logger.warning("Firebase client not available, running in standalone mode")
    else:
        logger.info("No Firebase project configured, running in standalone mode")
    
    # For standalone/development mode, run a test session on startup
    if not config.firebase_project_id:
        logger.info("Running test session in 3 seconds...")
        import time
        time.sleep(3)
        
        test_schedule = Schedule(
            id="test-session",
            cron="",
            enabled=True,
            prompt_variant="default",
        )
        session_manager.start_session(test_schedule)
        
        # Wait for session to complete
        while session_manager._current_session:
            time.sleep(1)
    else:
        # Keep running for Firebase mode
        logger.info("Device agent running. Press Ctrl+C to stop.")
        while True:
            import time
            time.sleep(60)


def run_demo_session() -> None:
    """Run a single demo voice session for testing."""
    logger.info("Running demo voice session")
    
    config = DeviceConfig.load()
    
    led = get_led_controller()
    led.start()
    led.set_state(DeviceState.PROCESSING)
    
    session_manager = SessionManager(config)
    
    test_schedule = Schedule(
        id="demo-session",
        cron="",
        enabled=True,
        prompt_variant="default",
    )
    
    session_manager.start_session(test_schedule)
    
    # Wait for session to complete
    import time
    while session_manager._current_session and session_manager._current_session.is_active:
        time.sleep(0.5)
    
    # Wait a bit for cleanup
    time.sleep(2)
    
    session_manager.stop()
    led.stop()
    
    logger.info("Demo session complete")


def main() -> None:
    """Main entry point for the device agent."""
    logger.info("ElderCare Device Agent starting up...")
    logger.info("Data directory", path=str(DATA_DIR))
    
    # Ensure data directory exists
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    
    # Check for demo mode
    if "--demo" in sys.argv:
        run_demo_session()
        return
    
    # Check WiFi configuration
    if not is_wifi_configured():
        logger.info("WiFi not configured, starting BLE onboarding")
        start_ble_onboarding()
        return
    
    # Load configuration
    try:
        config = DeviceConfig.load()
    except ValueError as e:
        logger.error("Configuration error", error=str(e))
        logger.info("Set OPENAI_API_KEY environment variable or create secrets file")
        sys.exit(1)
    
    # Run normal operation
    run_normal_operation(config)


if __name__ == "__main__":
    main()
