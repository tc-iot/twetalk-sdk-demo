package com.tencent.twetalk_sdk_demo.video

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.tencent.twetalk.protocol.ImageMessage
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VideoChatCameraManager(
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onImageCaptured: (ImageMessage) -> Unit
) {
    companion object {
        private const val TAG = "VideoChatCameraManager"

        // JPEG 压缩质量（0-100）：视觉理解场景通常不需要很高质量，降低体积更利于端到端时延
        private const val JPEG_QUALITY = 60


        // 预览目标分辨率（作为参考，实际会根据设备能力调整）
        private val TARGET_RESOLUTION = Size(1280, 720)

        // 拍照目标分辨率：只作用于拍照，不影响预览
        private val PHOTO_TARGET_RESOLUTION = Size(640, 480)
    }

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    // 拍照图片处理线程（避免在主线程做 ImageProxy->Bitmap->JPEG 这种重活）
    private val imageProcessingExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "twetalk-photo").apply { priority = Thread.NORM_PRIORITY }
    }
    
    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(previewView.context)
        
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(previewView.context))
    }
    
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return
        val previewResolutionSelector = createResolutionSelector()
        val photoResolutionSelector = createPhotoResolutionSelector()

        // 预览配置（保持现有策略，不改预览分辨率选择）
        val preview = Preview.Builder()
            .setResolutionSelector(previewResolutionSelector)
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
        
        // 图片捕获配置（只限制拍照分辨率）
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(photoResolutionSelector)
            .setJpegQuality(JPEG_QUALITY)
            .setTargetRotation(previewView.display.rotation)
            .build()
        
        // 摄像头选择器
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        try {
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
        } catch (e: Exception) {
            Log.e("CameraManager", "Failed to bind camera.", e)
        }
    }
    
    fun captureImage() {
        val imageCapture = imageCapture ?: return
        val mainExecutor = ContextCompat.getMainExecutor(previewView.context)

        imageCapture.takePicture(
            imageProcessingExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    imageProcessingExecutor.execute {
                        try {
                            // 转换为 JPEG ByteArray（后台线程）
                            val imgMsg = imageProxyToImageMessage(image)
                            // 回到主线程派发回调（避免调用方误用 UI）
                            mainExecutor.execute { onImageCaptured(imgMsg) }
                        } catch (t: Throwable) {
                            Log.e(TAG, "Failed to process captured image", t)
                        } finally {
                            image.close()
                        }
                    }
                }
                
                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraManager", "Failed to capture.", exception)
                }
            }
        )
    }
    
    fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }

        bindCameraUseCases()
    }

    /**
     * 创建智能分辨率选择器（用于预览）
     */
    private fun createResolutionSelector(): ResolutionSelector {
        val context = previewView.context

        // 获取屏幕尺寸作为参考
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // 计算合适的目标分辨率（不超过屏幕尺寸，保持16:9或4:3比例）
        val targetSize = calculateOptimalResolution(screenWidth, screenHeight)

        Log.d(TAG, "Screen size: ${screenWidth}x${screenHeight}, target size: ${targetSize.width}x${targetSize.height}")

        return ResolutionSelector.Builder()
            // 设置分辨率策略：优先选择最接近目标分辨率的
            .setResolutionStrategy(
                ResolutionStrategy(
                    targetSize,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            // 设置宽高比策略：优先 16:9，降级到 4:3
            .setAspectRatioStrategy(
                AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            )
            .build()
    }

    /**
     * 创建拍照用分辨率选择器：只作用于拍照，不影响预览。
     * 目标：避免拍照走到 1920x1080 这种大图导致编码/发送阻塞与 GC 抖动。
     */
    private fun createPhotoResolutionSelector(): ResolutionSelector {
        return ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    PHOTO_TARGET_RESOLUTION,
                    // 优先选择不高于目标分辨率的档位（更利于时延），实在没有再往上找
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .setAspectRatioStrategy(
                AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            )
            .build()
    }

    /**
     * 计算最佳分辨率
     * 考虑屏幕尺寸和常用分辨率
     */
    private fun calculateOptimalResolution(screenWidth: Int, screenHeight: Int): Size {
        // 常用的分辨率
        val commonResolutions = listOf(
            Size(1920, 1080), // Full HD
            Size(1280, 720),  // HD
            Size(960, 540),   // qHD
            Size(640, 480)    // VGA
        )

        // 选择不超过屏幕尺寸的最大分辨率
        val maxDimension = maxOf(screenWidth, screenHeight)

        return commonResolutions.firstOrNull {
            it.width <= maxDimension || it.height <= maxDimension
        } ?: TARGET_RESOLUTION
    }

    private fun imageProxyToImageMessage(image: ImageProxy): ImageMessage {
        val srcBitmap = image.toBitmap()

        // 获取旋转角度
        val rotationDegrees = image.imageInfo.rotationDegrees

        // 判断是否需要镜像
//        val needMirror = lensFacing == CameraSelector.LENS_FACING_FRONT

        val finalBitmap = if (rotationDegrees != 0) {
            rotateBitmap(srcBitmap, rotationDegrees)
        } else {
            srcBitmap
        }

        val (byteArray, finalWidth, finalHeight) = ByteArrayOutputStream().use { stream ->
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            Triple(stream.toByteArray(), finalBitmap.width, finalBitmap.height)
        }

        // 释放 bitmap，避免大图引发后续 GC 抖动
        if (finalBitmap !== srcBitmap) {
            finalBitmap.recycle()
        }
        srcBitmap.recycle()

        return ImageMessage(
            byteArray,
            finalWidth,
            finalHeight,
            ImageMessage.ImageFormat.JPEG
        )
    }

    private var rotationMatrix: Matrix? = null

    private fun rotateBitmap(
        source: Bitmap,
        degrees: Int,
        mirror: Boolean = false
    ): Bitmap {
        val matrix = rotationMatrix ?: Matrix().also { rotationMatrix = it }
        matrix.reset()

        // 镜像
        if (mirror) {
            matrix.preScale(-1f, 1f)
        }

        // 旋转
        if (degrees != 0) {
            matrix.postRotate(degrees.toFloat())
        }

        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true
        )
    }

    fun release() {
        cameraProvider?.unbindAll()
        camera = null
        imageCapture = null
        imageProcessingExecutor.shutdown()
    }
}
