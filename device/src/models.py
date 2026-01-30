"""Data models for the ElderCare device agent."""

from dataclasses import dataclass, field
from datetime import datetime
from typing import Any


@dataclass
class Schedule:
    """A scheduled check-in from Firestore.
    
    Attributes:
        id: Unique schedule identifier.
        cron: Cron expression for scheduling (e.g., "0 9 * * *" for 9 AM daily).
        enabled: Whether this schedule is active.
        prompt_variant: Which prompt variant to use (default, brief, detailed).
    """
    id: str
    cron: str
    enabled: bool = True
    prompt_variant: str = "default"
    
    @classmethod
    def from_dict(cls, doc_id: str, data: dict[str, Any]) -> "Schedule":
        """Create a Schedule from a Firestore document."""
        return cls(
            id=doc_id,
            cron=data.get("cron", ""),
            enabled=data.get("enabled", True),
            prompt_variant=data.get("prompt_variant", "default"),
        )


@dataclass
class Device:
    """Device registration information.
    
    Attributes:
        id: Unique device identifier.
        owner_uid: Firebase UID of the device owner.
        timezone: Device timezone for scheduling.
        status: Current device status.
        last_seen: Last time device checked in.
    """
    id: str
    owner_uid: str | None = None
    timezone: str = "America/New_York"
    status: str = "online"
    last_seen: datetime | None = None
    
    @classmethod
    def from_dict(cls, doc_id: str, data: dict[str, Any]) -> "Device":
        """Create a Device from a Firestore document."""
        last_seen = data.get("last_seen")
        if last_seen and hasattr(last_seen, "timestamp"):
            last_seen = datetime.fromtimestamp(last_seen.timestamp())
        
        return cls(
            id=doc_id,
            owner_uid=data.get("owner_uid"),
            timezone=data.get("tz", "America/New_York"),
            status=data.get("status", "online"),
            last_seen=last_seen,
        )


@dataclass
class SessionEvent:
    """A recorded check-in session event.
    
    Attributes:
        id: Unique event identifier.
        device_id: Device that recorded this event.
        schedule_id: Schedule that triggered this session.
        timestamp: When the session occurred.
        duration_seconds: How long the session lasted.
        user_transcript: What the elderly person said.
        assistant_transcript: What the AI companion said.
        summary: Brief summary of the session.
        action: Follow-up action taken (none, continue, alert).
        urgency_level: Urgency level if alert was sent (1-5).
    """
    id: str
    device_id: str
    schedule_id: str
    timestamp: datetime
    duration_seconds: int
    user_transcript: str = ""
    assistant_transcript: str = ""
    summary: str = ""
    action: str = "none"
    urgency_level: int = 1
    
    def to_dict(self) -> dict[str, Any]:
        """Convert to Firestore document format."""
        return {
            "device_id": self.device_id,
            "schedule_id": self.schedule_id,
            "timestamp": self.timestamp,
            "duration_seconds": self.duration_seconds,
            "user_transcript": self.user_transcript,
            "assistant_transcript": self.assistant_transcript,
            "summary": self.summary,
            "action": self.action,
            "urgency_level": self.urgency_level,
        }
