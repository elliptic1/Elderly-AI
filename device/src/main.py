import asyncio
import structlog

from . import ble_onboarding, firebase_client
from .models import Schedule
from .session_manager import SessionManager

logger = structlog.get_logger()

session_manager = SessionManager()


def is_wifi_configured() -> bool:
    """Return True if the device has Wi-Fi credentials configured."""
    # For now, we'll assume it's not configured
    return False


def start_ble_onboarding() -> None:
    """Start the BLE onboarding process."""
    logger.info("Starting BLE onboarding...")
    asyncio.run(ble_onboarding.start_advertising())


def run_normal_operation() -> None:
    """Start the normal operation of the device agent."""
    logger.info("Starting normal operation...")
    if firebase_client.initialize_firebase():
        firebase_client.listen_for_schedules(handle_schedule)


def handle_schedule(schedule: Schedule) -> None:
    """Handle a newly received schedule from Firestore."""
    logger.info("Received schedule", schedule=schedule)
    session_manager.start_session(schedule)


def main() -> None:
    """Main entry point for the device agent."""
    logger.info("Device agent starting up...")
    if is_wifi_configured():
        run_normal_operation()
    else:
        logger.info("Wi-Fi not configured.")
        start_ble_onboarding()


if __name__ == "__main__":
    main()
