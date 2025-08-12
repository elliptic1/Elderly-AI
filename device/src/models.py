from dataclasses import dataclass
from typing import Optional


@dataclass
class OnboardingState:
    """Tracks values received during BLE onboarding."""

    ssid: Optional[str] = None
    psk: Optional[str] = None
    timezone: Optional[str] = None
    claim_token: Optional[str] = None

    def is_complete(self) -> bool:
        return all([self.ssid, self.psk, self.timezone, self.claim_token])


@dataclass
class Schedule:
    """Represents a scheduled check-in retrieved from Firestore."""

    id: str
    cron: str
    prompt_variant: str
