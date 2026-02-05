# TWeTalk Android SDK

TWeTalk Android SDK 提供了基于 WebSocket 和 TRTC 两种传输方式的 AI 对话能力，支持音频对话、文本转录、视觉模态等功能。

## SDK 接入

### 1. 引入依赖

在 `settings.gradle` 中添加 Maven 仓库：

```kotlin
maven {
    url = uri("https://oss.sonatype.org/content/repositories/snapshots")
}
maven {
    name = "Central Portal Snapshots"
    url = uri("https://central.sonatype.com/repository/maven-snapshots/")
}
```

### 2. WebSocket 模式

#### 2.1 添加依赖

```kotlin
implementation("com.tencent.twetalk:twetalk-android:1.1.7-SNAPSHOT")
```

#### 2.2 初始化客户端

WebSocket 模式需要先通过 MQTT 获取 WebSocket URL 和 Token，然后创建客户端：

```kotlin
// MQTT 回调中获取 WebSocket 配置
mqttCallback = object : MqttManager.MqttConnectionCallback {
    override fun onMessageReceived(topic: String?, method: String?, params: Map<String?, Any?>?) {
        when (method) {
            MqttManager.REPLY_QUERY_WEBSOCKET_URL -> {
                // 创建认证配置
                val authConfig = TWeTalkConfig.AuthConfig(
                    productId,
                    deviceName,
                    params["token"] as String,  // MQTT 返回的 token
                    audioType,  // "pcm" 或 "opus"
                    language    // "zh" 或 "en"
                ).apply {
                    baseUrl = params["websocket_url"] as String
                }

                // 创建 SDK 配置
                val config = TWeTalkConfig.builder()
                    .authConfig(authConfig)
                    .build()

                // 创建客户端
                client = DefaultTWeTalkClient(config)
                client.addListener(this@YourActivity)
                client.connect()
            }
        }
    }
}

// 请求 WebSocket URL
val params = mapOf("connect_type" to MqttManager.WebSocketConnectType.TALK.value)
mqttManager?.queryWebSocketUrl(params)
```

#### 2.3 实现监听器

```kotlin
class YourActivity : TWeTalkClientListener {
    override fun onStateChanged(state: ConnectionState) {
        when (state) {
            ConnectionState.CONNECTED -> {
                // 连接成功，可以开始对话
            }
            ConnectionState.CLOSED -> {
                // 连接已关闭
            }
            else -> {}
        }
    }

    override fun onRecvAudio(audio: ByteArray, sampleRate: Int, channels: Int, format: AudioFormat) {
        // 播放接收到的音频数据
    }

    override fun onRecvTalkMessage(type: TWeTalkMessage.TWeTalkMessageType, text: String?) {
        when (type) {
            TWeTalkMessage.TWeTalkMessageType.BOT_TRANSCRIPTION -> {
                // 显示机器人转录文本
            }
            TWeTalkMessage.TWeTalkMessageType.BOT_LLM_TEXT -> {
                // 显示 LLM 流式输出
            }
            else -> {}
        }
    }

    override fun onRecvCallMessage(stream: CallStream, subType: CallSubType, data: TweCallMessage.TweCallData) {
        // 处理通话消息（如接听、挂断等）
    }

    override fun onMetrics(metrics: MetricEvent?) {
        // 可选：监听性能指标
    }

    override fun onError(error: Throwable?) {
        // 处理错误
    }
}
```

#### 2.4 发送数据

```kotlin
// 发送音频数据
client.sendCustomAudioData(audioData, sampleRate = 16000, channels = 1)

// 发送自定义消息
val msg = """{"type":"custom","data":"hello"}"""
client.sendCustomMsg(msg)

// 发送图片
client.sendImage(imageMessage)
```

#### 2.5 断开连接

```kotlin
client.disconnect()  // 断开连接
client.close()       // 释放资源
```

### 3. TRTC 模式

#### 3.1 添加依赖

```kotlin
implementation("com.tencent.twetalk:twetalk-android-trtc:1.0.8-SNAPSHOT")
```

#### 3.2 初始化客户端

TRTC 模式无需通过 MQTT 获取房间信息，可直接用特定配置参数对 Client 进行初始化：

```kotlin
// 使用设备三元组初始化配置
config = TRTCConfig(applicationContext)
config.productId = productId
config.deviceName = deviceName
config.deviceSecret = deviceSecret
config.language = language
config.useTRTCRecord = true

client = DefaultTRTCClient(config)
```

#### 3.3 实现监听器

```kotlin
class YourActivity : TRTCClientListener {
    override fun onStateChanged(state: TRTCClientState?) {
        when (state) {
            TRTCClientState.ON_CALLING -> {
                // 开始通话
            }
            TRTCClientState.LEAVED -> {
                // 已离开房间
            }
            else -> {}
        }
    }

    override fun onRecvAudio(audio: ByteArray, sampleRate: Int, channels: Int, format: AudioFormat) {
        // 播放接收到的音频数据
    }

    override fun onRecvTalkMessage(type: TWeTalkMessage.TWeTalkMessageType, text: String?) {
        // 处理对话消息
    }

    override fun onRecvCallMessage(stream: CallStream, subType: CallSubType, data: TweCallMessage.TweCallData) {
        // 处理通话消息
    }

    override fun onMetrics(metrics: MetricEvent?) {
        // 可选：监听性能指标
    }

    override fun onError(errCode: Int, errMsg: String?) {
        // 处理错误
    }
}
```

#### 3.4 发送数据

```kotlin
// 发送音频数据
client.sendCustomAudioData(audioData, sampleRate = 16000, channels = 1)

// 发送自定义消息
val msg = """{"type":"custom","data":"hello"}"""
client.sendCustomMsg(msg)
```

#### 3.5 停止对话

```kotlin
client.stopConversation()  // 停止对话
client.destroy()           // 释放资源
```


## 消息类型说明

### 连接状态 (ConnectionState / TRTCClientState)

**WebSocket 模式：**
- `IDLE` - 空闲状态，未连接
- `CONNECTING` - 正在连接
- `CONNECTED` - 已连接
- `RECONNECTING` - 正在重连
- `CLOSING` - 正在关闭
- `CLOSED` - 已关闭

**TRTC 模式：**
- `IDLE` - 空闲状态
- `ENTERING` - 正在进入房间
- `ENTERED` - 已进入房间
- `WAITING` - 等待对方进入
- `ON_CALLING` - 通话中
- `LEAVING` - 正在离开房间
- `LEAVED` - 已离开房间

### 对话消息类型 (TWeTalkMessageType)

**服务器端消息：**
- `BOT_READY` - 机器人已连接并准备接收消息
- `ERROR` - 机器人初始化错误
- `REQUEST_IMAGE` - 服务端请求图片

**转录消息：**
- `USER_TRANSCRIPTION` - 本地用户语音转文本
- `BOT_TRANSCRIPTION` - 机器人完整文本转录
- `USER_STARTED_SPEAKING` - 用户开始说话
- `USER_STOPPED_SPEAKING` - 用户停止说话
- `BOT_STARTED_SPEAKING` - 机器人开始说话
- `BOT_STOPPED_SPEAKING` - 机器人停止说话

**LLM 消息：**
- `USER_LLM_TEXT` - 聚合后的用户输入文本
- `BOT_LLM_TEXT` - LLM 返回的流式 token
- `BOT_LLM_STARTED` - 机器人 LLM 推理开始
- `BOT_LLM_STOPPED` - 机器人 LLM 推理结束

**TTS 消息：**
- `BOT_TTS_TEXT` - 机器人 TTS 文本输出
- `BOT_TTS_STARTED` - 机器人 TTS 响应开始
- `BOT_TTS_STOPPED` - 机器人 TTS 响应结束

### 通话消息类型

**通话流方向 (CallStream)：**
- `DEVICE_TO_USER` - 设备呼叫小程序
- `USER_TO_DEVICE` - 小程序呼叫设备

**通话子类型 (CallSubType)：**
- `USER_CALLING` - 正在呼叫
- `USER_ANSWERED` - 小程序已接听，通话中
- `USER_REJECT` - 小程序拒接
- `USER_ERROR` - 呼叫出错
- `USER_TIMEOUT` - 呼叫超时
- `USER_BUSY` - 小程序占线
- `USER_HANGUP` - 小程序挂断

### 音频格式 (AudioFormat)

- `PCM` - PCM 原始音频格式（采样率 16kHz，单声道）
- `OPUS` - Opus 编码格式（采样率 48kHz，单声道）

## Demo 使用指引

本项目的 `app` 模块提供了完整的 Demo 示例，演示了 WebSocket 和 TRTC 两种模式的使用。

### 运行 Demo

1. 使用 Android Studio 打开项目
2. 选择 `app` 模块
3. 编译并安装到 Android 设备或模拟器
4. 在设备绑定界面中输入相应的配置信息（产品 ID、设备名称、设备密钥）
5. 选择 WebSocket 或 TRTC 模式开始对话
6. 如需使用微信小程序通话功能，请先点击主界面右上角的听筒按钮进入通讯录配置界面添加小程序 openid 和昵称

### Demo 功能

- **WebSocket 模式**：支持 AI 语音对话、文本转录、视频聊天、微信通话等功能
- **TRTC 模式**：支持 AI 语音对话、文本转录等功能

**注意事项：**

- 使用 Demo 前需要在腾讯云物联网开发平台创建产品并获取相关配置信息。
- 如设备不支持 AEC 功能，在视频聊天时可能会出现 AI 自问自答的情况，此时要想获取更好的对话体验，需将视频聊天对话方式修改为**按键说话**，目前操作是切到 release_demo_for_paixueji 分支，再重新编译安装运行，后续会尽快优化这方面的体验，敬请期待。

## 注意事项

1. **MQTT 连接**：两种模式都依赖 MQTT 来获取连接参数，请确保 MQTT 连接正常
2. **音频格式**：
    - PCM 格式：采样率 16kHz，单声道
    - OPUS 格式：采样率 48kHz，单声道
3. **资源释放**：使用完毕后请务必调用相应的清理方法释放资源
4. **线程安全**：SDK 已处理内部线程安全，回调会在指定线程中执行
