
import asyncio
import structlog

from bleak import BleakServer
from bleak.backends.characteristic import BleakGATTCharacteristic
from bleak.backends.service import BleakGATTService

logger = structlog.get_logger()

ONBOARDING_SERVICE_UUID = "0000FFF0-0000-1000-8000-00805F9B34FB"
SSID_CHAR_UUID = "0000FFF1-0000-1000-8000-00805F9B34FB"
PSK_CHAR_UUID = "0000FFF2-0000-1000-8000-00805F9B34FB"
TIMEZONE_CHAR_UUID = "0000FFF3-0000-1000-8000-00805F9B34FB"
CLAIM_TOKEN_CHAR_UUID = "0000FFF4-0000-1000-8000-00805F9B34FB"
STATUS_CHAR_UUID = "0000FFF5-0000-1000-8000-00805F9B34FB"

class OnboardingState:
    def __init__(self):
        self.ssid = None
        self.psk = None
        self.timezone = None
        self.claim_token = None

    def is_complete(self):
        return all([self.ssid, self.psk, self.timezone, self.claim_token])

state = OnboardingState()

def write_ssid(value: bytearray):
    state.ssid = value.decode()
    logger.info(f"Received SSID: {state.ssid}")

def write_psk(value: bytearray):
    state.psk = value.decode()
    logger.info(f"Received PSK: {state.psk}")

def write_timezone(value: bytearray):
    state.timezone = value.decode()
    logger.info(f"Received Timezone: {state.timezone}")

from . import provisioning

def write_claim_token(value: bytearray):
    state.claim_token = value.decode()
    logger.info(f"Received Claim Token: {state.claim_token}")
    if state.is_complete():
        logger.info("All credentials received, attempting to provision.")
        if provisioning.connect_to_wifi(state.ssid, state.psk):
            if provisioning.claim_device(state.claim_token):
                logger.info("Device provisioned successfully.")
                # In a real implementation, we would stop advertising
                # and start the main application logic.
            else:
                logger.error("Failed to provision device.")
        else:
            logger.error("Failed to connect to Wi-Fi.")

async def start_advertising():
    """
    Starts BLE advertising for the onboarding service.
    """
    logger.info("Starting BLE advertising...")

    try:
        service = BleakGATTService(ONBOARDING_SERVICE_UUID)
        service.add_characteristic(BleakGATTCharacteristic(SSID_CHAR_UUID, ["write"], write_ssid))
        service.add_characteristic(BleakGATTCharacteristic(PSK_CHAR_UUID, ["write"], write_psk))
        service.add_characteristic(BleakGATTCharacteristic(TIMEZONE_CHAR_UUID, ["write"], write_timezone))
        service.add_characteristic(BleakGATTCharacteristic(CLAIM_TOKEN_CHAR_UUID, ["write"], write_claim_token))
        service.add_characteristic(BleakGATTCharacteristic(STATUS_CHAR_UUID, ["notify"]))

        async with BleakServer(service) as server:
            logger.info(f"Advertising service {ONBOARDING_SERVICE_UUID}")
            await asyncio.Event().wait()  # Keep the server running indefinitely
    except Exception as e:
        logger.error("Failed to start BLE advertising", error=e)

if __name__ == "__main__":
    # This is for testing the BLE advertising directly
    asyncio.run(start_advertising())
