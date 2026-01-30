"""Session analysis for determining follow-up actions."""

from __future__ import annotations

import json
from dataclasses import dataclass
from enum import Enum
from typing import Callable

import structlog

from .config import (
    EMERGENCY_KEYWORDS,
    CONCERNING_KEYWORDS,
    POSITIVE_KEYWORDS,
    OPENAI_CHAT_URL,
)

logger = structlog.get_logger()


class AnalysisAction(Enum):
    """Follow-up action based on conversation analysis."""
    NONE = "none"               # Everything okay
    CONTINUE = "continue"       # Person wants to keep talking
    ALERT_CAREGIVER = "alert"   # Send notification to caregiver


@dataclass
class AnalysisResult:
    """Result of conversation analysis."""
    action: AnalysisAction
    summary: str
    reasoning: str
    urgency_level: int  # 1-5, 5 being most urgent
    
    def to_dict(self) -> dict:
        return {
            "action": self.action.value,
            "summary": self.summary,
            "reasoning": self.reasoning,
            "urgency_level": self.urgency_level,
        }


def quick_analyze(user_transcript: str) -> AnalysisResult:
    """Perform fast local keyword-based analysis.
    
    This runs immediately after the session ends to provide
    a quick assessment. Deep analysis via API runs in parallel.
    
    Args:
        user_transcript: What the elderly person said.
        
    Returns:
        AnalysisResult with action and urgency level.
    """
    if not user_transcript or not user_transcript.strip():
        return AnalysisResult(
            action=AnalysisAction.NONE,
            summary="No response from person",
            reasoning="Person did not respond during check-in",
            urgency_level=2,  # Slightly concerning
        )
    
    lower_transcript = user_transcript.lower()
    
    # Check for emergency keywords (highest priority)
    for keyword in EMERGENCY_KEYWORDS:
        if keyword in lower_transcript:
            return AnalysisResult(
                action=AnalysisAction.ALERT_CAREGIVER,
                summary=f"Possible emergency mentioned: {keyword}",
                reasoning="Detected emergency keyword in transcript",
                urgency_level=5,
            )
    
    # Check for concerning keywords
    for keyword in CONCERNING_KEYWORDS:
        if keyword in lower_transcript:
            return AnalysisResult(
                action=AnalysisAction.ALERT_CAREGIVER,
                summary="Concerning content mentioned",
                reasoning=f"Detected concerning keyword: {keyword}",
                urgency_level=3,
            )
    
    # Check for positive responses
    for keyword in POSITIVE_KEYWORDS:
        if keyword in lower_transcript:
            return AnalysisResult(
                action=AnalysisAction.NONE,
                summary="Person seems to be doing well",
                reasoning="Detected positive response",
                urgency_level=1,
            )
    
    # Default: inconclusive but okay
    return AnalysisResult(
        action=AnalysisAction.NONE,
        summary="Check-in completed normally",
        reasoning="No concerning keywords detected",
        urgency_level=1,
    )


async def deep_analyze(
    api_key: str,
    user_transcript: str,
    assistant_transcript: str,
) -> AnalysisResult:
    """Perform deep analysis using GPT-4o-mini.
    
    This provides more nuanced understanding of the conversation
    context and can catch issues that keyword matching misses.
    
    Args:
        api_key: OpenAI API key.
        user_transcript: What the elderly person said.
        assistant_transcript: What the AI companion said.
        
    Returns:
        AnalysisResult from GPT analysis.
    """
    import aiohttp
    
    prompt = _build_analysis_prompt(user_transcript, assistant_transcript)
    
    request_body = {
        "model": "gpt-4o-mini",
        "max_tokens": 500,
        "temperature": 0.3,
        "messages": [
            {
                "role": "system",
                "content": (
                    "You are an AI assistant that analyzes conversations between an elderly person "
                    "and a check-in companion. Your job is to determine if any follow-up action is needed. "
                    "Respond ONLY with a JSON object containing: action (NONE, CONTINUE, or ALERT_CAREGIVER), "
                    "summary (1-2 sentence summary), reasoning (why you chose this action), "
                    "and urgencyLevel (1-5, where 5 is emergency)."
                ),
            },
            {
                "role": "user",
                "content": prompt,
            },
        ],
    }
    
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {api_key}",
    }
    
    try:
        async with aiohttp.ClientSession() as session:
            async with session.post(
                OPENAI_CHAT_URL,
                json=request_body,
                headers=headers,
            ) as response:
                if response.status != 200:
                    error_text = await response.text()
                    logger.error("Analysis API error", status=response.status, error=error_text)
                    raise Exception(f"API request failed: {response.status}")
                
                result = await response.json()
                return _parse_analysis_response(result)
                
    except Exception as e:
        logger.error("Deep analysis failed", error=str(e))
        # Fall back to quick analysis
        return quick_analyze(user_transcript)


def _build_analysis_prompt(user_transcript: str, assistant_transcript: str) -> str:
    """Build the prompt for GPT analysis."""
    parts = [
        "Please analyze this check-in conversation with an elderly person:\n",
        f"\nASSISTANT (AI Check-in companion): {assistant_transcript or '[No response recorded]'}",
        f"\nELDERLY PERSON: {user_transcript or '[No response recorded]'}",
        "\n\nBased on this conversation, determine:",
        "\n1. Is the person okay or is there cause for concern?",
        "\n2. Did they mention any health issues, falls, pain, or emergencies?",
        "\n3. Do they seem lonely and want to continue talking?",
        "\n4. Should their caregiver be notified?",
        "\n\nRespond with a JSON object only.",
    ]
    return "".join(parts)


def _parse_analysis_response(api_response: dict) -> AnalysisResult:
    """Parse the GPT response into an AnalysisResult."""
    choices = api_response.get("choices", [])
    
    if not choices:
        return AnalysisResult(
            action=AnalysisAction.NONE,
            summary="Unable to analyze conversation",
            reasoning="No response from analysis",
            urgency_level=1,
        )
    
    content = choices[0].get("message", {}).get("content", "")
    
    # Clean up markdown code blocks if present
    content = content.strip()
    if content.startswith("```json"):
        content = content[7:]
    if content.startswith("```"):
        content = content[3:]
    if content.endswith("```"):
        content = content[:-3]
    content = content.strip()
    
    try:
        analysis = json.loads(content)
    except json.JSONDecodeError:
        logger.error("Failed to parse analysis JSON", content=content)
        return AnalysisResult(
            action=AnalysisAction.NONE,
            summary="Check-in completed",
            reasoning="Analysis parsing failed",
            urgency_level=1,
        )
    
    # Parse action
    action_str = analysis.get("action", "NONE").upper()
    if action_str == "ALERT_CAREGIVER":
        action = AnalysisAction.ALERT_CAREGIVER
    elif action_str == "CONTINUE":
        action = AnalysisAction.CONTINUE
    else:
        action = AnalysisAction.NONE
    
    # Extract other fields
    summary = analysis.get("summary", "Check-in completed")
    reasoning = analysis.get("reasoning", "")
    urgency_level = analysis.get("urgencyLevel", 1)
    
    # Clamp urgency level
    urgency_level = max(1, min(5, int(urgency_level)))
    
    return AnalysisResult(
        action=action,
        summary=summary,
        reasoning=reasoning,
        urgency_level=urgency_level,
    )


class SessionAnalyzer:
    """High-level session analyzer that runs both quick and deep analysis."""
    
    def __init__(self, api_key: str):
        self.api_key = api_key
    
    def analyze_sync(
        self,
        user_transcript: str,
        assistant_transcript: str,
    ) -> AnalysisResult:
        """Synchronous analysis (quick only).
        
        Use this for immediate response after session ends.
        """
        return quick_analyze(user_transcript)
    
    async def analyze_async(
        self,
        user_transcript: str,
        assistant_transcript: str,
        on_quick_result: Callable[[AnalysisResult], None] | None = None,
    ) -> AnalysisResult:
        """Asynchronous analysis with both quick and deep passes.
        
        Args:
            user_transcript: What the elderly person said.
            assistant_transcript: What the AI companion said.
            on_quick_result: Optional callback for immediate quick result.
            
        Returns:
            Deep analysis result.
        """
        # Quick analysis first
        quick_result = quick_analyze(user_transcript)
        if on_quick_result:
            on_quick_result(quick_result)
        
        # Deep analysis
        deep_result = await deep_analyze(
            self.api_key,
            user_transcript,
            assistant_transcript,
        )
        
        # If deep analysis finds something that quick missed, log it
        if (
            deep_result.action == AnalysisAction.ALERT_CAREGIVER
            and quick_result.action != AnalysisAction.ALERT_CAREGIVER
        ):
            logger.warning(
                "Deep analysis found issue missed by quick analysis",
                quick_action=quick_result.action.value,
                deep_action=deep_result.action.value,
                deep_summary=deep_result.summary,
            )
        
        return deep_result
