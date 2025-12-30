#
# Copyright 2024 ElderCare AI
#
# Product makefile for ElderCare device based on Panther (Pixel 7 Pro)
#
# This extends the base Panther product with ElderCare customizations.
#

# Inherit from Panther
$(call inherit-product, device/google/panther/aosp_panther.mk)

# Product identifiers
PRODUCT_NAME := eldercare_panther
PRODUCT_DEVICE := panther
PRODUCT_BRAND := ElderCare
PRODUCT_MODEL := ElderCare Voice Companion
PRODUCT_MANUFACTURER := ElderCare

# Include ElderCare system app
PRODUCT_PACKAGES += \
    ElderCareApp

# ElderCare-specific properties
PRODUCT_SYSTEM_PROPERTIES += \
    ro.eldercare.version=1.0 \
    ro.eldercare.device=panther

# Overlay for ElderCare-specific configurations
PRODUCT_PACKAGE_OVERLAYS += \
    vendor/elderly-ai/aosp/device-overlay/panther

# Pre-grant runtime permissions to ElderCareApp
PRODUCT_COPY_FILES += \
    vendor/elderly-ai/aosp/config/eldercare-permissions.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/eldercare-permissions.xml

# Default wallpaper and boot animation (optional)
# PRODUCT_COPY_FILES += \
#     vendor/elderly-ai/aosp/assets/bootanimation.zip:$(TARGET_COPY_OUT_SYSTEM)/media/bootanimation.zip
