package com.espressif.espbleapp.protocol

object EslCommands {
    const val GET_AP_STATUS = "get_ap_status"
    const val LIST_TAGS = "list_tags"
    const val SCAN_START = "scan_start"
    const val SCAN_STOP = "scan_stop"
    const val PROVISION_TAG = "provision_tag"
    const val CANCEL_PROVISION = "cancel_provision"
    const val UNASSOCIATE = "unassociate"
    const val PING = "ping"
    const val LED_CONTROL = "led_control"
    const val DISPLAY_IMAGE = "display_image"
    const val REFRESH_DISPLAY = "refresh_display"
    const val UPLOAD_IMAGE_BEGIN = "upload_image_begin"
    const val UPLOAD_IMAGE_CHUNK = "upload_image_chunk"
    const val UPLOAD_IMAGE_END = "upload_image_end"
    const val TRANSFER_IMAGE_TO_TAG = "transfer_image_to_tag"
    const val UPLOAD_BADGE_IMAGE_BEGIN = "upload_badge_image_begin"
    const val UPLOAD_BADGE_IMAGE_CHUNK = "upload_badge_image_chunk"
    const val UPLOAD_BADGE_IMAGE_END = "upload_badge_image_end"
    const val TRANSFER_BADGE_IMAGE_TO_TAG = "transfer_badge_image_to_tag"
    const val LIST_BADGE_IMAGES = "list_badge_images"
    const val UPLOAD_RAW = "upload_raw"
}

object EslEvents {
    const val SCAN_RESULT = "scan_result"
    const val TAG_STATE_CHANGED = "tag_state_changed"
    const val COMMAND_RESULT = "command_result"
    const val IMAGE_TRANSFER_PROGRESS = "image_transfer_progress"
    const val IMAGE_UPLOADED = "image_uploaded"
    const val BADGE_IMAGE_UPLOADED = "badge_image_uploaded"
    const val BADGE_TRANSFER_PROGRESS = "badge_transfer_progress"
    const val LOG = "log"
    const val AP_STATUS_CHANGED = "ap_status_changed"
    const val PROVISION_FAILED = "provision_failed"
    const val RECOVERY_SUMMARY_CHANGED = "recovery_summary_changed"
    const val TAG_RECOVERY_CHANGED = "tag_recovery_changed"
}

object EslErrors {
    const val INVALID_ARGUMENT = "invalid_argument"
    const val UNSUPPORTED_CMD = "unsupported_cmd"
    const val BUSY = "busy"
    const val TAG_NOT_FOUND = "tag_not_found"
    const val NOT_CONNECTED = "not_connected"
    const val TIMEOUT = "timeout"
    const val IMAGE_INVALID = "image_invalid"
    const val NOT_READY = "not_ready"
    const val INTERNAL = "internal"
    const val UPGRADE_REQUIRED = "upgrade_required"
}
