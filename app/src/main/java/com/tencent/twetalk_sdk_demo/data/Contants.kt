package com.tencent.twetalk_sdk_demo.data

object Constants {
    const val KEY_CHAT_BUNDLE = "connection_config"  // 跳转 chat 的 bundle
    const val KEY_DEVICE_INFO_PREF = "device_info"
    const val KEY_SECRET_INFO_PREF = "secret_info"
    const val KEY_CONNECT_PARAMS_PREF = "connect_params"
    const val KEY_CALL_CONFIG_PREF = "call_config"  // 通话配置

    /** 具体参数 **/
    const val KEY_CONNECTION_TYPE = "connection_type"
    const val KEY_AUDIO_TYPE = "audio_type"
    const val KEY_LANGUAGE = "language"
    const val KEY_PRODUCT_ID = "product_id"
    const val KEY_DEVICE_NAME = "device_name"
    const val KEY_DEVICE_SECRET = "device_secret"
    const val KEY_SECRET_ID = "secret_id"
    const val KEY_SECRET_KEY = "secret_key"
    const val KEY_SDK_APP_ID = "sdk_app_id"
    const val KEY_SDK_SECRET_KEY = "sdk_secret_key"
    const val KEY_USER_ID = "user_id"
    const val KEY_VIDEO_MODE = "video_mode"
    const val KEY_PUSH_TO_TALK = "push_to_talk"
    const val KEY_BOT_ID = "bot_id"
    const val KEY_SEND_TEXT = "send_text"

    /** 通话配置参数 **/
    const val KEY_WXA_APP_ID = "wxa_app_id"
    const val KEY_WXA_MODEL_ID = "wxa_model_id"
    const val KEY_CONTACTS = "contacts"  // 通讯录 JSON

    /** 通话页面传参 **/
    const val KEY_CALL_BUNDLE = "call_config_bundle"
    const val KEY_CALL_TYPE = "call_type"  // incoming / outgoing
    const val KEY_CALL_NICKNAME = "call_nickname"
    const val KEY_CALL_OPEN_ID = "call_open_id"
    const val KEY_CALL_ROOM_ID = "call_room_id"

    /** 默认值 **/
    const val DEFAULT_WXA_APP_ID = "wx9e8fbc98ceac2628"
    const val DEFAULT_WXA_MODEL_ID = "DYEbVE9kfjAONqnWsOhXgw"
}