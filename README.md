# ElderCare Voice Companion

<p align="center">
  <img src="https://raw.githubusercontent.com/genez-io/Elderly-AI/main/docs/images/logo.svg" alt="ElderCare Voice Companion Logo" width="200"/>
</p>

<p align="center">
  <strong>A Raspberry Pi–powered, AI-driven check-in system for remote caregivers.</strong>
</p>

<p align="center">
  <a href="https://github.com/genez-io/Elderly-AI/actions/workflows/ci.yml"><img src="https://github.com/genez-io/Elderly-AI/actions/workflows/ci.yml/badge.svg" alt="CI Status"></a>
  <a href="https://github.com/genez-io/Elderly-AI/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License"></a>
</p>

---

ElderCare Voice Companion is a system designed to help remote caregivers stay connected with their loved ones. It schedules voice conversations, uses AI to conduct them, and provides summaries to the caregiver's phone.

## Features

*   **Hands-Free Check-Ins**: The device automatically starts conversations on a schedule.
*   **Real-Time AI Conversations**: Natural, low-latency conversations powered by OpenAI.
*   **Easy Setup**: Onboarding via a mobile app and Bluetooth.
*   **Cloud-Connected**: Schedules and settings are synced from the cloud.
*   **Session Summaries**: Get summaries of each check-in on your phone.
*   **Multi-Device Support**: Cover multiple rooms or locations.

## How It Works

The system consists of a Raspberry Pi device, a mobile app for the caregiver, and a cloud backend.

1.  **The Device**: A Raspberry Pi with a speaker and microphone that runs the voice conversations.
2.  **The Mobile App**: An Android app for caregivers to manage schedules, view summaries, and set up new devices.
3.  **The Cloud**: Firebase services for authentication, database, and cloud functions.

For a more detailed look at the system architecture, see the [System Architecture documentation](docs/system_architecture.md).

## Getting Started

### Prerequisites

*   A Raspberry Pi (3B+ or newer)
*   A speaker and microphone for the Raspberry Pi
*   An Android phone for the caregiver

### Device Setup

1.  Flash a Raspberry Pi with the latest Raspberry Pi OS Lite (64-bit).
2.  Install the required dependencies:
    ```bash
    sudo apt-get update && sudo apt-get install -y bluez libasound2-dev portaudio19-dev
    pip install bleak pyaudio websockets apscheduler google-cloud-firestore structlog
    ```
3.  Set up a systemd service to run the device agent on boot.

### Mobile App Setup

The mobile app is still under development. You can find the source code in the `mobile-android` directory.

## Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for more details.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.