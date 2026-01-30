"""Configuration settings for the ElderCare device agent."""

from dataclasses import dataclass
from pathlib import Path
import os

# Audio settings
AUDIO_RATE = 16_000  # 16kHz required by OpenAI Realtime API
AUDIO_CHANNELS = 1   # Mono
AUDIO_CHUNK = 1024   # Samples per buffer
AUDIO_FORMAT_WIDTH = 2  # 16-bit PCM = 2 bytes

# Session limits
MAX_SESSION_DURATION_SEC = 3 * 60  # 3 minutes
SILENCE_TIMEOUT_SEC = 20           # 20 seconds of silence triggers commit

# OpenAI Realtime API
OPENAI_REALTIME_URL = "wss://api.openai.com/v1/realtime?model=gpt-4o-realtime-preview"
OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions"

# ElderCare system prompt
ELDERCARE_SYSTEM_PROMPT = """You are a caring and friendly companion checking in on an elderly person. \
Your goal is to have a brief, warm conversation to see how they're doing. \
Start by greeting them warmly and asking how they are today. \
Listen carefully to their response. \
If they mention any concerning symptoms, falls, emergencies, or requests for help, \
acknowledge their concern with empathy and let them know help will be notified. \
Keep the conversation brief (under 2 minutes) but caring. \
End by wishing them well."""

# Session analysis keywords
EMERGENCY_KEYWORDS = [
    "help me", "can't get up", "fell down", "chest pain", "can't breathe",
    "call 911", "ambulance", "emergency", "bleeding", "heart attack"
]

CONCERNING_KEYWORDS = [
    "hurt", "pain", "dizzy", "fell", "fall", "sick", "scared",
    "alone", "lonely", "sad", "help", "doctor", "hospital"
]

POSITIVE_KEYWORDS = [
    "good", "fine", "great", "okay", "well", "thanks", "thank you",
    "wonderful", "nice", "lovely", "happy"
]

# File paths
DATA_DIR = Path("/var/lib/eldercare")
SECRETS_FILE = DATA_DIR / "secrets.json"
SESSIONS_DIR = DATA_DIR / "sessions"
ALERTS_DIR = DATA_DIR / "alerts"

# Alternative paths for development
if not DATA_DIR.exists():
    DATA_DIR = Path.home() / ".eldercare"
    SECRETS_FILE = DATA_DIR / "secrets.json"
    SESSIONS_DIR = DATA_DIR / "sessions"
    ALERTS_DIR = DATA_DIR / "alerts"


@dataclass
class DeviceConfig:
    """Runtime configuration loaded from secrets/environment."""
    openai_api_key: str
    device_id: str
    firebase_project_id: str | None = None
    timezone: str = "America/New_York"
    
    @classmethod
    def load(cls) -> "DeviceConfig":
        """Load config from environment or secrets file."""
        import json
        
        # Try environment first
        api_key = os.environ.get("OPENAI_API_KEY")
        device_id = os.environ.get("ELDERCARE_DEVICE_ID", "dev-device")
        firebase_project = os.environ.get("FIREBASE_PROJECT_ID")
        timezone = os.environ.get("ELDERCARE_TIMEZONE", "America/New_York")
        
        # Fall back to secrets file
        if not api_key and SECRETS_FILE.exists():
            with open(SECRETS_FILE) as f:
                secrets = json.load(f)
                api_key = secrets.get("openai_api_key", api_key)
                device_id = secrets.get("device_id", device_id)
                firebase_project = secrets.get("firebase_project_id", firebase_project)
                timezone = secrets.get("timezone", timezone)
        
        if not api_key:
            raise ValueError("OPENAI_API_KEY not set in environment or secrets file")
        
        return cls(
            openai_api_key=api_key,
            device_id=device_id,
            firebase_project_id=firebase_project,
            timezone=timezone,
        )
