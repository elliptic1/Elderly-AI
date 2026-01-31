#
# Copyright 2024 ElderCare AI
#
# AndroidProducts.mk - Registers ElderCare products with the build system
#
# This file is located at vendor/elderly-ai/aosp/AndroidProducts.mk in the AOSP tree.
#

PRODUCT_MAKEFILES := \
    $(LOCAL_DIR)/products/eldercare_panther.mk

COMMON_LUNCH_CHOICES := \
    eldercare_panther-trunk_staging-userdebug \
    eldercare_panther-trunk_staging-user
