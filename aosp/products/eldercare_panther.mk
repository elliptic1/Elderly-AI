#
# Copyright 2024 ElderCare AI
#
# Product makefile for ElderCare device based on Panther (Pixel 7 Pro)
#
# This extends the base Panther product with ElderCare customizations.
#

# Inherit from Panther (Pixel 7 Pro)
$(call inherit-product, device/google/pantah/aosp_panther.mk)

# Product identifiers
PRODUCT_NAME := eldercare_panther
PRODUCT_DEVICE := panther
PRODUCT_BRAND := ElderCare
PRODUCT_MODEL := ElderCare Voice Companion
PRODUCT_MANUFACTURER := ElderCare

# Register vendor/elderly-ai for Soong module discovery
PRODUCT_SOONG_NAMESPACES += \
    vendor/elderly-ai/ElderCareApp

# Include ElderCare system app
PRODUCT_PACKAGES += \
    ElderCareApp

# ElderCare-specific properties
PRODUCT_SYSTEM_PROPERTIES += \
    ro.eldercare.version=1.0 \
    ro.eldercare.device=panther

# Overlay for ElderCare-specific configurations
PRODUCT_PACKAGE_OVERLAYS += \
    vendor/elderly-ai/device-overlay/panther

# Pre-grant runtime permissions to ElderCareApp
PRODUCT_COPY_FILES += \
    vendor/elderly-ai/config/eldercare-permissions.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/eldercare-permissions.xml

# Add artifact path exceptions for ElderCare files installed to system partition
PRODUCT_ARTIFACT_PATH_REQUIREMENT_ALLOWED_LIST += \
    system/priv-app/ElderCareApp/ElderCareApp.apk \
    system/etc/permissions/eldercare-permissions.xml
