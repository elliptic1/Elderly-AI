
import structlog
import asyncio

logger = structlog.get_logger()

def is_wifi_configured():
    """
    Checks if the device is connected to a Wi-Fi network.
    This is a placeholder and should be replaced with a real implementation.
    """
    # For now, we'll assume it's not configured
    return False

from . import ble_onboarding

def start_ble_onboarding():
    """
    Starts the BLE onboarding process.
    """
    logger.info("Starting BLE onboarding...")
    asyncio.run(ble_onboarding.start_advertising())

from . import firebase_client

def run_normal_operation():
    """
    Starts the normal operation of the device agent.
    """
    logger.info("Starting normal operation...")
    if firebase_client.initialize_firebase():
        firebase_client.listen_for_schedules(handle_schedule)

from . import session_manager

def handle_schedule(schedule):
    """
    This function is called when a new schedule is received.
    """
    logger.info(f"Received schedule: {schedule}")
    session_manager.start_session(schedule)

def main():
    """
    Main entry point for the device agent.
    """
    logger.info("Device agent starting up...")

    if is_wifi_configured():
        run_normal_operation()
    else:
        logger.info("Wi-Fi not configured.")
        start_ble_onboarding()

if __name__ == "__main__":
    main()
